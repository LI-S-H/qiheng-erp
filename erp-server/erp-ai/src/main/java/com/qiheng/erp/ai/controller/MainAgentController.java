package com.qiheng.erp.ai.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.qiheng.erp.ai.clarification.ClarificationConfirmationService;
import com.qiheng.erp.ai.agent.AgentModelFailure;
import com.qiheng.erp.ai.agent.AskUserValidationHook;
import com.qiheng.erp.ai.agent.MainAgentService;
import com.qiheng.erp.ai.agent.RunEventChannel;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.ai.clarification.ClarificationAnswer;
import com.qiheng.erp.ai.action.PurchaseOrderActionReceipt;
import com.qiheng.erp.ai.draft.PurchaseDraftReceipt;
import com.qiheng.erp.ai.order.OrderConfirmationService;
import com.qiheng.erp.ai.sales.SalesOrderActionReceipt;
import com.qiheng.erp.ai.sales.SalesOrderDraftReceipt;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.common.util.IdUtil;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.HashMap;
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
    private final ObjectProvider<ClarificationConfirmationService> clarifications;
    private final ObjectProvider<OrderConfirmationService> confirmations;
    private final ConcurrentHashMap<String, RunEventChannel> channels = new ConcurrentHashMap<>();

    /**
     * 注入主智能体与追问服务；两者都可能因未启用模型而缺失。
     *
     * @param agents 主智能体服务
     * @param clarifications 追问服务
     */
    public MainAgentController(ObjectProvider<MainAgentService> agents,
                               ObjectProvider<ClarificationConfirmationService> clarifications) {
        this(agents, clarifications, null);
    }

    /**
     * 注入单据确认服务。
     *
     * @param agents 主智能体服务
     * @param clarifications 追问服务
     * @param confirmations 单据确认服务
     */
    @Autowired
    public MainAgentController(ObjectProvider<MainAgentService> agents,
                               ObjectProvider<ClarificationConfirmationService> clarifications,
                               ObjectProvider<OrderConfirmationService> confirmations) {
        this.agents = agents;
        this.clarifications = clarifications;
        this.confirmations = confirmations;
    }

    /** 本轮用户输入。 */
    public record MessageRequest(@NotBlank String content) {}

    /** 本轮追问答案。 */
    public record AnswerRequest(@NotEmpty @Size(max = 20) List<ClarificationAnswer> answers,
                                @Size(max = 10000) String note) {}

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
        // 启动与恢复共用同一套事件转换，后续新增事件只改一处。
        Flux<ServerSentEvent<Object>> producer = toEvents(outputs, requestedRunId);
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
     * 校验用户答案并恢复检查点。
     *
     * @param conversationId 会话编号
     * @param questionRequestId 追问编号
     * @param body 答案内容
     * @return 空结果
     * @throws Exception 校验或恢复失败
     */
    @PostMapping("/{conversationId}/questions/{questionRequestId}/answer")
    public Result<Void> answer(@PathVariable String conversationId, @PathVariable String questionRequestId,
                               @Valid @RequestBody AnswerRequest body) throws Exception {
        long id = IdUtil.parseRequiredLongId(conversationId, "conversationId");
        ClarificationConfirmationService service = clarifications.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("追问确认功能未启用");
        }
        long userId = UserContext.requireCurrentUser().getUserId();
        try {
            var resumed = service.resolveForUser(userId, id, questionRequestId, body.answers(), body.note());
            startBackgroundRun(id, userId, resumed.outputs(), resumed.runId());
            var active = channel(id);
            if (active != null) {
                synchronized (active) {
                    active.answered(questionRequestId);
                }
            }
        } catch (MainAgentService.ConfirmationExpiredException error) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "确认已失效，请刷新会话后重试");
        } catch (IllegalArgumentException error) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), error.getMessage());
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
     * @throws Exception 校验或写入失败
     */
    @PostMapping("/{conversationId}/drafts/{draftRequestId}/resolve")
    public Result<Void> resolveDraft(@PathVariable String conversationId, @PathVariable String draftRequestId,
                                     @RequestBody PurchaseDraftReceipt body) throws Exception {
        long id = IdUtil.parseRequiredLongId(conversationId, "conversationId");
        OrderConfirmationService service = requireConfirmations("采购草稿确认功能未启用");
        long userId = UserContext.requireCurrentUser().getUserId();
        var resumed = service.resolveDraftForUser(userId, id, draftRequestId, body);
        startBackgroundRun(id, userId, resumed.outputs(), resumed.runId());
        return Result.ok();
    }

    /**
     * 提交用户对采购单动作（提交审核、取消）的处置结果。
     *
     * @param conversationId 会话编号
     * @param actionRequestId 动作请求编号
     * @param body 处置结果
     * @return 空结果
     * @throws Exception 校验或写入失败
     */
    @PostMapping("/{conversationId}/actions/{actionRequestId}/resolve")
    public Result<Void> resolveAction(@PathVariable String conversationId, @PathVariable String actionRequestId,
                                      @RequestBody PurchaseOrderActionReceipt body) throws Exception {
        long id = IdUtil.parseRequiredLongId(conversationId, "conversationId");
        OrderConfirmationService service = requireConfirmations("采购单操作功能未启用");
        long userId = UserContext.requireCurrentUser().getUserId();
        var resumed = service.resolveActionForUser(userId, id, actionRequestId, body);
        startBackgroundRun(id, userId, resumed.outputs(), resumed.runId());
        return Result.ok();
    }

    /**
     * 提交用户对销售草稿的处置结果。
     *
     * @param conversationId 会话编号
     * @param draftRequestId 草稿请求编号
     * @param body 处置结果
     * @return 空结果
     */
    @PostMapping("/{conversationId}/sales-drafts/{draftRequestId}/resolve")
    public Result<Void> resolveSalesDraft(@PathVariable String conversationId, @PathVariable String draftRequestId,
                                          @RequestBody SalesOrderDraftReceipt body) throws Exception {
        long id = IdUtil.parseRequiredLongId(conversationId, "conversationId");
        OrderConfirmationService service = requireConfirmations("销售草稿确认功能未启用");
        long userId = UserContext.requireCurrentUser().getUserId();
        var resumed = service.resolveDraftForUser(userId, id, draftRequestId, body);
        startBackgroundRun(id, userId, resumed.outputs(), resumed.runId());
        return Result.ok();
    }

    /**
     * 提交用户对销售单动作（提交审核、取消）的处置结果。
     *
     * @param conversationId 会话编号
     * @param actionRequestId 动作请求编号
     * @param body 处置结果
     * @return 空结果
     */
    @PostMapping("/{conversationId}/sales-actions/{actionRequestId}/resolve")
    public Result<Void> resolveSalesAction(@PathVariable String conversationId, @PathVariable String actionRequestId,
                                           @RequestBody SalesOrderActionReceipt body) throws Exception {
        long id = IdUtil.parseRequiredLongId(conversationId, "conversationId");
        OrderConfirmationService service = requireConfirmations("销售单操作功能未启用");
        long userId = UserContext.requireCurrentUser().getUserId();
        var resumed = service.resolveActionForUser(userId, id, actionRequestId, body);
        startBackgroundRun(id, userId, resumed.outputs(), resumed.runId());
        return Result.ok();
    }

    /** 取得单据确认服务；未启用时返回明确的业务错误。 */
    private OrderConfirmationService requireConfirmations(String message) {
        if (confirmations == null || confirmations.getIfAvailable() == null) {
            throw new IllegalStateException(message);
        }
        return confirmations.getObject();
    }

    /** 启动一次恢复运行，并把事件写入会话通道供页面重新订阅。 */
    @SuppressWarnings("unchecked")
    private void startBackgroundRun(long conversationId, long userId, Flux<NodeOutput> outputs, String runId) {
        String key = userId + ":" + conversationId;
        var channel = new RunEventChannel(runId);
        synchronized (channels) {
            var previous = channels.put(key, channel);
            if (previous != null) {
                previous.publish("run_failed", Map.of("message", "新的确认操作已接管本次会话"));
            }
        }
        toEvents(outputs, runId)
                .doFinally(signal -> Schedulers.parallel().schedule(
                        () -> channels.remove(key, channel), 2, TimeUnit.MINUTES))
                .subscribe(value -> channel.publish(value.event(), (Map<String, Object>) value.data()),
                        error -> channel.publish("run_failed", Map.of("message", "本次任务执行失败，请稍后重试。")),
                        () -> {
                            if (!channel.isClosed()) {
                                channel.publish("run_failed", Map.of("message", "任务流异常结束，请重新进入会话确认。"));
                            }
                        });
    }

    /** 把图事件转换成前端协议事件；启动和恢复共用。 */
    private Flux<ServerSentEvent<Object>> toEvents(Flux<NodeOutput> outputs, String requestedRunId) {
        return Flux.defer(() -> {
            var runId = new AtomicReference<>(requestedRunId);
            var messageId = new AtomicReference<>(UUID.randomUUID().toString());
            var finalText = new AtomicReference<String>();
            var modelFailure = new AtomicInteger();
            var validationFailure = new AtomicInteger();
            var completed = new AtomicInteger();
            var paused = new AtomicInteger();
            var emittedToolTexts = new HashSet<String>();
            var fallbackTextIds = new HashMap<String, String>();
            return outputs.<ServerSentEvent<Object>>handle((output, sink) -> {
                String node = output.node();
                if ("run_started".equals(node)) {
                    runId.set(String.valueOf(output.state().data().get("runId")));
                    sink.next(event(node, output.state().data()));
                } else if ("model_tool_fallback".equals(node)) {
                    if (output.state().data().get("callId") instanceof String callId) fallbackTextIds.put(callId, messageId.get());
                    messageId.set(UUID.randomUUID().toString());
                } else if (node != null && node.endsWith("message_recording.afterModel")) {
                    Object messages = output.state().data().get("messages");
                    if (messages instanceof List<?> list && !list.isEmpty()
                            && list.getLast() instanceof AssistantMessage assistant
                            && !assistant.getToolCalls().isEmpty()) {
                        Object invalidCallId = output.state().data().get(AskUserValidationHook.INVALID_CALL_KEY);
                        boolean invalidAskUser = invalidCallId instanceof String value
                                && assistant.getToolCalls().stream().anyMatch(call -> value.equals(call.id()));
                        String text = assistant.getText();
                        // 非法追问尝试的说明文字不进入可见消息，只保留流水。
                        if (!invalidAskUser && text != null && !text.isBlank()) {
                            sink.next(event("text_completed", Map.of("messageId", messageId.get(), "text", text)));
                            assistant.getToolCalls().forEach(call -> emittedToolTexts.add(call.id()));
                        }
                        messageId.set(UUID.randomUUID().toString());
                    }
                } else if ("confirmation_text".equals(node)) {
                    var data = output.state().data();
                    if (data.get("callId") instanceof String callId && data.get("text") instanceof String text
                            && !text.isBlank() && emittedToolTexts.add(callId)) {
                        String textId = fallbackTextIds.getOrDefault(callId, messageId.get());
                        sink.next(event("text_completed", Map.of("messageId", textId, "text", text)));
                        if (textId.equals(messageId.get())) messageId.set(UUID.randomUUID().toString());
                    }
                } else if ("question".equals(node)) {
                    var data = new LinkedHashMap<String, Object>(output.state().data());
                    data.put("runId", runId.get());
                    sink.next(event("question", data));
                    completed.set(1);
                    paused.set(1);
                } else if ("purchase_draft".equals(node)) {
                    var data = new LinkedHashMap<String, Object>(output.state().data());
                    data.put("runId", runId.get());
                    sink.next(event("purchase_draft", data));
                    completed.set(1);
                    paused.set(1);
                } else if ("purchase_action".equals(node)) {
                    var data = new LinkedHashMap<String, Object>(output.state().data());
                    data.put("runId", runId.get());
                    sink.next(event("purchase_action", data));
                    completed.set(1);
                    paused.set(1);
                } else if ("sales_order_draft".equals(node)) {
                    var data = new LinkedHashMap<String, Object>(output.state().data());
                    data.put("runId", runId.get());
                    sink.next(event("sales_order_draft", data));
                    completed.set(1);
                    paused.set(1);
                } else if ("sales_order_action".equals(node)) {
                    var data = new LinkedHashMap<String, Object>(output.state().data());
                    data.put("runId", runId.get());
                    sink.next(event("sales_order_action", data));
                    completed.set(1);
                    paused.set(1);
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
                        if (AgentModelFailure.isFailure(last.getText())) {
                            modelFailure.set(1);
                        } else {
                            finalText.set(last.getText());
                        }
                    }
                    completed.set(1);
                    Object failedRunId = output.state().data().get(AskUserValidationHook.FAILED_STATE_KEY);
                    if (failedRunId instanceof String value && value.equals(runId.get())) {
                        validationFailure.set(1);
                    }
                }
            }).concatWith(Flux.defer(() -> {
                if (completed.get() != 1) {
                    return Flux.empty();
                }
                if (paused.get() == 1) {
                    return Flux.just(event("run_completed", Map.of("runId", runId.get(), "status", "WAITING_USER")));
                }
                if (validationFailure.get() == 1) {
                    var failure = event("run_failed", Map.of(
                            "runId", runId.get() == null ? "" : runId.get(), "status", "FAILED",
                            "code", "ASK_USER_VALIDATION_FAILED",
                            "message", "本次追问连续多次参数无效，已停止本轮。"));
                    return finalText.get() == null ? Flux.just(failure) : Flux.just(
                            event("text_completed", Map.of("messageId", messageId.get(), "text", finalText.get())),
                            failure);
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
                    .onErrorResume(error -> Flux.just(event("run_failed", error instanceof MainAgentService.ConfirmationExpiredException
                            ? Map.of("runId", runId.get() == null ? "" : runId.get(), "status", "FAILED",
                            "code", "CONFIRMATION_EXPIRED", "message", error.getMessage())
                            : Map.of("runId", runId.get() == null ? "" : runId.get(), "status", "FAILED",
                            "message", "本次任务执行失败，请稍后重试。"))));
        });
    }

    /** 把图事件包装成前端约定的事件流帧。 */
    private static ServerSentEvent<Object> event(String name, Object data) {
        return ServerSentEvent.builder(data).event(name).build();
    }
}
