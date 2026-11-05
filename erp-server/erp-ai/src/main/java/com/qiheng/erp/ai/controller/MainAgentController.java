package com.qiheng.erp.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.qiheng.erp.ai.clarification.ClarificationService;
import com.qiheng.erp.ai.agent.MainAgentService;
import com.qiheng.erp.ai.agent.RunEventChannel;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.ai.clarification.ClarificationAnswer;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.common.util.IdUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

/** 主智能体运行和用户追问接口。 */
@RestController
@RequestMapping("/ai/assistant/conversations")
@SaCheckPermission("ai:assistant:use")
public class MainAgentController {
    private final ObjectProvider<MainAgentService> agents;
    private final ObjectProvider<ClarificationService> clarifications;
    private final ConcurrentHashMap<String, RunEventChannel> channels = new ConcurrentHashMap<>();

    public MainAgentController(ObjectProvider<MainAgentService> agents, ObjectProvider<ClarificationService> clarifications) {
        this.agents = agents;
        this.clarifications = clarifications;
    }

    public record MessageRequest(@NotBlank String content) {}
    public record AnswerRequest(@NotEmpty List<ClarificationAnswer> answers, String note) {}

    /** 运行主智能体，将图事件转换为前端协议。 */
    @PostMapping(value = "/{conversationId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> stream(@PathVariable String conversationId,
                                                 @Valid @RequestBody MessageRequest body) throws Exception {
        long id = IdUtil.parseRequiredLongId(conversationId, "conversationId");
        MainAgentService agent = agents.getObject();
        long userId = UserContext.requireCurrentUser().getUserId();
        String requestedRunId = UUID.randomUUID().toString();
        Flux<NodeOutput> outputs = agent.streamForCurrentUser(id, body.content(), requestedRunId);
        Flux<ServerSentEvent<Object>> producer = Flux.defer(() -> {
            var runId = new AtomicReference<>(requestedRunId);
            var messageId = new AtomicReference<>(UUID.randomUUID().toString());
            var finalText = new AtomicReference<String>();
            var completed = new AtomicInteger();
            return outputs.<ServerSentEvent<Object>>handle((output, sink) -> {
                String node = output.node();
                if ("run_started".equals(node)) {
                    runId.set(String.valueOf(output.state().data().get("runId")));
                    sink.next(event(node, output.state().data()));
                } else if ("model_tool_fallback".equals(node)) {
                    messageId.set(UUID.randomUUID().toString());
                } else if (node != null && node.endsWith("message_recording.afterModel")) {
                    // 使用图内有序完成节点，不依赖工具回调与模型流之间的线程调度。
                    Object messages = output.state().data().get("messages");
                    if (messages instanceof List<?> list && !list.isEmpty()
                            && list.getLast() instanceof org.springframework.ai.chat.messages.AssistantMessage assistant
                            && !assistant.getToolCalls().isEmpty()) {
                        String text = assistant.getText();
                        if (text != null && !text.isBlank()) {
                            sink.next(event("text_completed", Map.of("messageId", messageId.get(), "text", text)));
                        }
                        messageId.set(UUID.randomUUID().toString());
                    }
                } else if ("ask_user".equals(node)) {
                    var data = new LinkedHashMap<String, Object>(output.state().data());
                    data.put("runId", runId.get());
                    sink.next(event("question", data));
                } else if ("tool_started".equals(node) || "tool_finished".equals(node)) {
                    var data = new LinkedHashMap<String, Object>(output.state().data());
                    data.put("runId", runId.get());
                    sink.next(event(node, data));
                } else if (output instanceof StreamingOutput<?> streaming
                        && streaming.getOutputType() == OutputType.AGENT_MODEL_STREAMING
                        && streaming.chunk() != null && !streaming.chunk().isEmpty()) {
                    sink.next(event("text_delta", Map.of("messageId", messageId.get(), "delta", streaming.chunk())));
                } else if (output.isEND()) {
                    var messages = output.state().data().get("messages");
                    if (messages instanceof java.util.List<?> list && !list.isEmpty()
                            && list.getLast() instanceof org.springframework.ai.chat.messages.AssistantMessage last
                            && last.getToolCalls().isEmpty() && last.getText() != null && !last.getText().isBlank()) {
                        finalText.set(last.getText());
                    }
                    completed.set(1);
                }
            }).concatWith(Flux.defer(() -> {
                if (completed.get() != 1) {
                    return Flux.empty();
                }
                var end = event("run_completed", Map.of("runId", runId.get(), "status", "COMPLETED"));
                return finalText.get() == null ? Flux.just(end) : Flux.just(
                        event("text_completed", Map.of("messageId", messageId.get(), "text", finalText.get())), end);
            }))
                    .onErrorResume(error -> Flux.just(event("run_failed", Map.of(
                            "runId", runId.get() == null ? "" : runId.get(), "status", "FAILED",
                            "message", "本次任务执行失败，请稍后重试。"))));
        });
        return Flux.defer(() -> {
            String key = userId + ":" + id;
            var channel = new RunEventChannel(requestedRunId);
            synchronized (channels) {
                var existing = channels.get(key);
                if (existing != null && !existing.isClosed()) {
                    return Flux.error(new IllegalStateException("会话正在执行其他任务"));
                }
                channels.put(key, channel);
            }
            // 先登记首个订阅再启动任务，避免任务快速输出时首屏遗漏事件。
            return Flux.create(sink -> {
                var subscription = channel.subscribe().subscribe(value -> sink.next(event(value.event(), value.data())), sink::error, sink::complete);
                sink.onDispose(subscription);
                // 后端独立订阅运行流；页面断开只移除页面订阅，不取消任务。
                producer.doFinally(signal -> reactor.core.scheduler.Schedulers.parallel().schedule(
                                () -> channels.remove(key, channel), 2, java.util.concurrent.TimeUnit.MINUTES))
                        .subscribe(value -> channel.publish(value.event(), (Map<String, Object>) value.data()),
                                error -> channel.publish("run_failed", Map.of("message", "本次任务执行失败，请稍后重试。")),
                                () -> {
                                    if (!channel.isClosed()) {
                                        channel.publish("run_failed", Map.of("message", "任务流异常结束，请重新进入会话确认。"));
                                    }
                                });
            });
        });
    }

    /** 查询本进程中的活跃任务；历史消息和最终状态由现有会话接口提供。 */
    @GetMapping("/{conversationId}/runtime")
    public Result<Map<String, Object>> runtime(@PathVariable String conversationId) {
        long id = IdUtil.parseRequiredLongId(conversationId, "conversationId");
        agents.getObject().currentForCurrentUser(id);
        var channel = channel(id);
        return Result.ok(channel == null ? Map.of("status", agents.getObject().latestStatusForCurrentUser(id)) : channel.snapshot());
    }

    /** 订阅已有任务，不会再次启动模型。 */
    @GetMapping(value = "/{conversationId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Object>> events(@PathVariable String conversationId) {
        long id = IdUtil.parseRequiredLongId(conversationId, "conversationId");
        agents.getObject().currentForCurrentUser(id);
        var channel = channel(id);
        if (channel == null) {
            return Flux.empty();
        }
        return channel.subscribe().map(value -> event(value.event(), value.data()));
    }

    private RunEventChannel channel(long conversationId) {
        String key = UserContext.requireCurrentUser().getUserId() + ":" + conversationId;
        var channel = channels.get(key);
        if (channel != null && channel.expired()) { channels.remove(key, channel); return null; }
        return channel;
    }

    /** 将用户答案交给正在等待的追问工具。 */
    @PostMapping("/{conversationId}/questions/{questionRequestId}/answer")
    public Result<Void> answer(@PathVariable String conversationId, @PathVariable String questionRequestId,
                               @Valid @RequestBody AnswerRequest body) {
        long id = IdUtil.parseRequiredLongId(conversationId, "conversationId");
        var active = channel(id);
        if (active == null) {
            clarifications.getObject().answerForCurrentUser(id, questionRequestId, body.answers(), body.note());
        } else {
            // 答案生效与快照切换在同一把锁内，避免重连时取到已经回答的旧问题。
            synchronized (active) {
                clarifications.getObject().answerForCurrentUser(id, questionRequestId, body.answers(), body.note());
                active.answered(questionRequestId);
            }
        }
        return Result.ok();
    }

    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder(data).event(name).build();
    }
}
