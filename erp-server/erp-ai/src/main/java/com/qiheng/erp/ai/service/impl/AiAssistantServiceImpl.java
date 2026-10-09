package com.qiheng.erp.ai.service.impl;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.qiheng.erp.ai.domain.dto.AiChatRequest;
import com.qiheng.erp.ai.domain.dto.AiMessageCreateCommand;
import com.qiheng.erp.ai.domain.vo.AiChatMessageVO;
import com.qiheng.erp.ai.domain.vo.AiChatResponseVO;
import com.qiheng.erp.ai.service.IAiAssistantService;
import com.qiheng.erp.ai.service.IAiConversationService;
import com.qiheng.erp.ai.service.IAiMessageService;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.exception.ServiceUnavailableBizException;
import com.qiheng.erp.security.context.UserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 请求准备、真实历史回灌和消息保存由同步/流式两种协议共用。 */
@Slf4j
@Service
public class AiAssistantServiceImpl implements IAiAssistantService {
    private static final String SAFE_ERROR = "AI 服务暂时不可用，请稍后重试";
    private final ObjectProvider<ReactAgent> agentProvider;
    private final IAiConversationService conversationService;
    private final IAiMessageService messageService;
    private final Duration responseTimeout;
    private final Set<String> activeConversations = ConcurrentHashMap.newKeySet();

    public AiAssistantServiceImpl(@Qualifier("orchestratorAgent") ObjectProvider<ReactAgent> agentProvider,
                                  IAiConversationService conversationService, IAiMessageService messageService,
                                  @Value("${erp.ai.response-timeout-seconds:300}") long responseTimeoutSeconds) {
        this.agentProvider = agentProvider;
        this.conversationService = conversationService;
        this.messageService = messageService;
        this.responseTimeout = Duration.ofSeconds(Math.max(1, responseTimeoutSeconds));
    }

    @Override
    public AiChatResponseVO send(AiChatRequest request) {
        Prepared prepared = prepare(request);
        try {
            String content = output(prepared).map(Message::getText)
                    .reduce(new StringBuilder(), StringBuilder::append)
                    .map(StringBuilder::toString).block(responseTimeout.plusSeconds(1));
            AiChatMessageVO message = persistReply(prepared, content);
            AiChatResponseVO response = new AiChatResponseVO();
            response.setConversationId(prepared.conversationId());
            response.setMessage(message);
            return response;
        } catch (Exception exception) {
            log.error("AI 请求失败 requestId={}, conversationId={}", prepared.requestId(), prepared.conversationId(), exception);
            throw new ServiceUnavailableBizException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        } finally {
            release(prepared);
        }
    }

    @Override
    public SseEmitter stream(AiChatRequest request) {
        Prepared prepared = prepare(request);
        // 模型先超时并发送ERROR，Servlet晚5秒兜底；Servlet进入超时态后已不允许继续发送。
        SseEmitter emitter = new SseEmitter(responseTimeout.plusSeconds(5).toMillis());
        StreamSession session = new StreamSession(prepared, emitter);
        emitter.onCompletion(session::disconnect);
        emitter.onError(ignored -> session.disconnect());
        emitter.onTimeout(() -> session.fail(new IllegalStateException("AI 响应超时")));
        // SSE写入和完成后的JDBC事务都可能阻塞，不能占用模型传输的网络线程。
        output(prepared).subscribeOn(Schedulers.boundedElastic())
                .publishOn(Schedulers.boundedElastic()).subscribe(session);
        return emitter;
    }

    private Prepared prepare(AiChatRequest request) {
        // 请求线程捕获身份，任何 Reactor 工作线程都不再访问 Sa-Token 上下文。
        Long userId = UserContext.getUserId();
        if (userId == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        if (request == null || request.getConversationId() == null || request.getConversationId() <= 0
                || !StringUtils.hasText(request.getMessage()) || request.getMessage().length() > 2000) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        Long conversationId = request.getConversationId();
        conversationService.getOwnedConversation(conversationId, userId);
        ReactAgent agent = agentProvider.getIfAvailable();
        if (agent == null) throw new ServiceUnavailableBizException(ErrorCode.AI_SERVICE_UNAVAILABLE);
        String lockKey = userId + ":" + conversationId;
        // 同一会话只能有一轮在执行，避免历史交叉和助手回复乱序。
        if (!activeConversations.add(lockKey)) throw new BizException(ErrorCode.OPERATION_FAILED);
        try {
            List<Message> input = new ArrayList<>();
            // 先读历史再保存本轮用户消息，本轮输入只出现一次。
            for (AiChatMessageVO history : messageService.recentForUser(conversationId, userId)) {
                if ("user".equals(history.getRole())) input.add(new UserMessage(history.getContent()));
                else if ("assistant".equals(history.getRole())) input.add(new AssistantMessage(history.getContent()));
            }
            String text = request.getMessage().trim();
            input.add(new UserMessage(text));
            messageService.appendForUser(conversationId, userId, command("user", text));
            RunnableConfig config = RunnableConfig.builder().threadId(lockKey)
                    .addMetadata("userId", userId).build();
            return new Prepared(agent, userId, conversationId, UUID.randomUUID().toString(), lockKey, input, config);
        } catch (RuntimeException exception) {
            activeConversations.remove(lockKey);
            throw exception;
        }
    }

    private Flux<Message> output(Prepared prepared) {
        long deadline = System.nanoTime() + responseTimeout.toNanos();
        return Flux.defer(() -> {
                    try {
                        return prepared.agent().streamMessages(prepared.input(), prepared.config());
                    } catch (Exception exception) {
                        return Flux.error(exception);
                    }
                }).filter(message -> message instanceof AssistantMessage
                        && message.getText() != null && !message.getText().isEmpty())
                .timeout(Mono.delay(responseTimeout), ignored -> Mono.delay(
                        Duration.ofNanos(Math.max(0, deadline - System.nanoTime()))));
    }

    private AiChatMessageVO persistReply(Prepared prepared, String content) {
        if (!StringUtils.hasText(content)) throw new IllegalStateException("模型未返回有效文本");
        // 独立短事务再次验证归属。失败或取消时不保存半截助手消息。
        return messageService.appendForUser(prepared.conversationId(), prepared.userId(), command("assistant", content));
    }

    private static AiMessageCreateCommand command(String role, String content) {
        AiMessageCreateCommand command = new AiMessageCreateCommand();
        command.setRole(role);
        command.setContent(content);
        return command;
    }

    private void release(Prepared prepared) {
        activeConversations.remove(prepared.lockKey());
    }

    private record Prepared(ReactAgent agent, Long userId, Long conversationId, String requestId, String lockKey,
                            List<Message> input, RunnableConfig config) { }

    /** 串行处理完成/取消/超时竞态，终态只触发一次，并取消底层订阅。 */
    private final class StreamSession extends BaseSubscriber<Message> {
        private final Prepared prepared;
        private final SseEmitter emitter;
        private final StringBuilder content = new StringBuilder();
        private boolean ended;

        private StreamSession(Prepared prepared, SseEmitter emitter) {
            this.prepared = prepared;
            this.emitter = emitter;
        }

        @Override
        protected synchronized void hookOnNext(Message message) {
            if (ended) return;
            content.append(message.getText());
            try {
                sendEvent("MESSAGE_DELTA", "contentDelta", message.getText());
            } catch (IOException | IllegalStateException exception) {
                disconnect();
            }
        }

        @Override
        protected synchronized void hookOnComplete() {
            if (ended) return;
            try {
                AiChatMessageVO message = persistReply(prepared, content.toString());
                sendEvent("MESSAGE_FINAL", "message", message);
                finish();
            } catch (Exception exception) {
                fail(exception);
            }
        }

        @Override
        protected void hookOnError(Throwable exception) {
            fail(exception);
        }

        private void sendEvent(String eventType, String field, Object value) throws IOException {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("eventType", eventType);
            event.put("requestId", prepared.requestId());
            event.put("conversationId", prepared.conversationId().toString());
            event.put(field, value);
            emitter.send(SseEmitter.event().name(eventType).data(event, MediaType.APPLICATION_JSON));
        }

        private synchronized void fail(Throwable exception) {
            if (ended) return;
            log.error("AI 流失败 requestId={}, conversationId={}", prepared.requestId(), prepared.conversationId(), exception);
            try {
                sendEvent("ERROR", "errorMessage", SAFE_ERROR);
            } catch (IOException | IllegalStateException ignored) {
                // 连接已断开，无需重试发送；仍必须释放会话锁和模型订阅。
            } finally {
                finish();
            }
        }

        private synchronized void disconnect() {
            if (ended) return;
            ended = true;
            cancel();
            release(prepared);
        }

        private void finish() {
            disconnect();
            emitter.complete();
        }
    }
}
