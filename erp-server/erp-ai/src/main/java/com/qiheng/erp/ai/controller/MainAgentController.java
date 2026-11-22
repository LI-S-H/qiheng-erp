package com.qiheng.erp.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.qiheng.erp.ai.clarification.ClarificationService;
import com.qiheng.erp.ai.agent.AgentModelFailure;
import com.qiheng.erp.ai.agent.MainAgentService;
import com.qiheng.erp.ai.agent.RunEventChannel;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.ai.clarification.ClarificationAnswer;
import com.qiheng.erp.ai.draft.PurchaseDraftReceipt;
import com.qiheng.erp.ai.draft.PurchaseDraftService;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.common.util.IdUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主智能体运行和用户追问接口。
 *
 * @author Li
 * @since 2026-10-27
 */
@RestController
@RequestMapping("/ai/assistant/conversations")
@SaCheckPermission("ai:assistant:use")
public class MainAgentController {
    private final ObjectProvider<MainAgentService> agents;
    private final ObjectProvider<ClarificationService> clarifications;
    private final ObjectProvider<PurchaseDraftService> drafts;
    private final ConcurrentHashMap<String, RunEventChannel> channels = new ConcurrentHashMap<>();

    /**
     * 注入主智能体与追问服务；两者都可能因未启用模型而缺失。
     *
     * @param agents 主智能体服务
     * @param clarifications 追问服务
     */
    public MainAgentController(ObjectProvider<MainAgentService> agents, ObjectProvider<ClarificationService> clarifications) {
        this(agents, clarifications, null);
    }

    /**
     * 注入全部等待用户处置的服务。
     *
     * @param agents 主智能体服务
     * @param clarifications 追问服务
     * @param drafts 采购草稿服务
     */
    @Autowired
    public MainAgentController(ObjectProvider<MainAgentService> agents, ObjectProvider<ClarificationService> clarifications,
                               ObjectProvider<PurchaseDraftService> drafts) {
        this.agents = agents;
        this.clarifications = clarifications;
        this.drafts = drafts;
    }

    /** 本轮用户输入。 */
    public record MessageRequest(@NotBlank String content) {}

    /** 本轮追问答案。 */
    public record AnswerRequest(@NotEmpty List<ClarificationAnswer> answers, String note) {}

    /**
     * 运行主智能体，将图事件转换为前端协议。
     *
     * @param conversationId 会话编号
     * @param body 本轮用户输入
     * @return 前端事件流
     * @throws Exception 图运行失败
     */
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
            var modelFailure = new AtomicInteger();
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
                            && list.getLast() instanceof AssistantMessage assistant
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
                } else if ("purchase_draft".equals(node)) {
                    // 草稿确认与追问共用等待通道，事件名区分页面展示方式。
                    var data = new LinkedHashMap<String, Object>(output.state().data());
                    data.put("runId", runId.get());
                    sink.next(event("purchase_draft", data));
                // 分析子过程与工具状态共用事件协议：事件名独立，过程数据在 data 内，并带父级工具调用编号。
                } else if ("tool_started".equals(node) || "tool_finished".equals(node)
                        || "analysis_started".equals(node) || "analysis_step".equals(node)
                        || "analysis_summary".equals(node) || "report_progress".equals(node) || "report_ready".equals(node)) {
                    var data = new LinkedHashMap<String, Object>(output.state().data());
                    data.put("runId", runId.get());
                    sink.next(event(node, data));
                } else if (output instanceof StreamingOutput<?> streaming
                        && streaming.getOutputType() == OutputType.AGENT_MODEL_STREAMING
                        && streaming.chunk() != null && !streaming.chunk().isEmpty()) {
                    sink.next(event("text_delta", Map.of("messageId", messageId.get(), "delta", streaming.chunk())));
                } else if (output.isEND()) {
                    var messages = output.state().data().get("messages");
                    if (messages instanceof List<?> list && !list.isEmpty()
                            && list.getLast() instanceof AssistantMessage last
                            && last.getToolCalls().isEmpty() && last.getText() != null && !last.getText().isBlank()) {
                        // 模型调用失败时框架返回错误文本，不能当作答复展示，改发失败事件。
                        if (AgentModelFailure.isFailure(last.getText())) {
                            modelFailure.set(1);
                        } else {
                            finalText.set(last.getText());
                        }
                    }
                    completed.set(1);
                }
            }).concatWith(Flux.defer(() -> {
                if (completed.get() != 1) {
                    return Flux.empty();
                }
                if (modelFailure.get() == 1) {
                    return Flux.just(event("run_failed", Map.of(
                            "runId", runId.get() == null ? "" : runId.get(), "status", "FAILED",
                            "code", AgentModelFailure.ERROR_CODE, "message", AgentModelFailure.RESULT_MESSAGE)));
                }
                var end = event("run_completed", finalText.get() == null
                        ? Map.of("runId", runId.get(), "status", "COMPLETED")
                        : Map.of("runId", runId.get(), "status", "COMPLETED", "finalMessageId", messageId.get()));
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
                producer.doFinally(signal -> Schedulers.parallel().schedule(
                                () -> channels.remove(key, channel), 2, TimeUnit.MINUTES))
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

    /**
     * 查询本进程中的活跃任务；历史消息和最终状态由现有会话接口提供。
     *
     * @param conversationId 会话编号
     * @return 运行状态或运行快照
     */
    @GetMapping("/{conversationId}/runtime")
    public Result<Map<String, Object>> runtime(@PathVariable String conversationId) {
        long id = IdUtil.parseRequiredLongId(conversationId, "conversationId");
        agents.getObject().currentForCurrentUser(id);
        var channel = channel(id);
        return Result.ok(channel == null ? Map.of("status", agents.getObject().latestStatusForCurrentUser(id)) : channel.snapshot());
    }

    /**
     * 订阅已有任务，不会再次启动模型。
     *
     * @param conversationId 会话编号
     * @return 事件流
     */
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

    /** 取得当前会话的事件通道；已过期时顺手清理。 */
    private RunEventChannel channel(long conversationId) {
        String key = UserContext.requireCurrentUser().getUserId() + ":" + conversationId;
        var channel = channels.get(key);
        if (channel != null && channel.expired()) {
            channels.remove(key, channel);
            return null;
        }
        return channel;
    }

    /**
     * 将用户答案交给正在等待的追问工具。
     *
     * @param conversationId 会话编号
     * @param questionRequestId 追问编号
     * @param body 答案内容
     * @return 空结果
     */
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

    /**
     * 提交用户对采购草稿的处置结果，结果随后交回等待中的模型。
     *
     * @param conversationId 会话编号
     * @param draftRequestId 草稿请求编号
     * @param body 处置结果
     * @return 空结果
     */
    @PostMapping("/{conversationId}/drafts/{draftRequestId}/resolve")
    public Result<Void> resolveDraft(@PathVariable String conversationId, @PathVariable String draftRequestId,
                                     @RequestBody PurchaseDraftReceipt body) {
        long id = IdUtil.parseRequiredLongId(conversationId, "conversationId");
        if (drafts == null || drafts.getIfAvailable() == null) {
            throw new IllegalStateException("采购草稿功能未启用");
        }
        var service = drafts.getObject();
        var active = channel(id);
        if (active == null) {
            service.resolveForCurrentUser(id, draftRequestId, body);
        } else {
            // 处置生效与快照切换在同一把锁内，避免重连时取到已经处置的草稿。
            synchronized (active) {
                service.resolveForCurrentUser(id, draftRequestId, body);
                active.draftResolved(draftRequestId);
            }
        }
        return Result.ok();
    }

    /** 把图事件包装成前端约定的事件流帧。 */
    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder(data).event(name).build();
    }
}
