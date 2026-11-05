package com.qiheng.erp.ai.agent;

import com.qiheng.erp.ai.clarification.ClarificationService;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import java.util.List;
import java.util.Map;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.conversation.ConversationExecutionLock;
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.qiheng.erp.ai.sql.scope.PermissionCodes;
import com.qiheng.erp.ai.sql.tool.SqlToolContext;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.service.LoginUserService;
import org.redisson.api.RLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/** 主智能体的内部流式入口；身份在调用线程确认后传入运行配置。 */
public final class MainAgentService {
    private static final Logger log = LoggerFactory.getLogger(MainAgentService.class);
    private final ReactAgent agent;
    private final RedisSaver saver;
    private final AiConversationMapper conversations;
    private final LoginUserService users;
    private final ConversationExecutionLock locks;
    private final ConversationMessageStore messageStore;
    private final ObjectMapper mapper;
    private final Map<String, RuntimeSnapshot> runs = new ConcurrentHashMap<>();
    private static final AtomicLong LOCK_OWNER = new AtomicLong(UUID.randomUUID().getMostSignificantBits());

    /** 当前进程中的运行信息；进程重启后不作为历史记录使用。 */
    public record RuntimeSnapshot(String runId, String status, Map<String, Object> question) {}

    /** 查询当前进程的运行状态及有效追问，先校验会话归属。 */
    public RuntimeSnapshot currentForCurrentUser(long conversationId) {
        return currentForUser(UserContext.requireCurrentUser().getUserId(), conversationId);
    }

    /** 当前进程没有运行时，仅将数据库中的未结束状态视为中断。 */
    public String latestStatusForCurrentUser(long conversationId) {
        long userId = UserContext.requireCurrentUser().getUserId();
        if (conversations.selectOwned(conversationId, userId) == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        String status = messageStore.latestRunStatus(userId, conversationId);
        return "RUNNING".equals(status) || "WAITING_USER".equals(status) ? "INTERRUPTED" : "IDLE";
    }

    RuntimeSnapshot currentForUser(long userId, long conversationId) {
        if (conversations.selectOwned(conversationId, userId) == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        return runs.get(userId + ":" + conversationId);
    }

    public MainAgentService(ReactAgent agent, RedisSaver saver, AiConversationMapper conversations, LoginUserService users,
                            ConversationExecutionLock locks, ConversationMessageStore messageStore,
                            ObjectMapper mapper) {
        this.agent = agent;
        this.saver = saver;
        this.conversations = conversations;
        this.users = users;
        this.locks = locks;
        this.messageStore = messageStore;
        this.mapper = mapper;
    }

    /** 从登录上下文捕获身份，避免流执行换线程后丢失用户。 */
    public Flux<NodeOutput> streamForCurrentUser(long conversationId, String input) throws GraphRunnerException {
        return streamForCurrentUser(conversationId, input, UUID.randomUUID().toString());
    }

    /** 由运行通道预分配轮次标识，确保快照与数据库消息使用同一标识。 */
    public Flux<NodeOutput> streamForCurrentUser(long conversationId, String input, String runId) throws GraphRunnerException {
        long userId = UserContext.requireCurrentUser().getUserId();
        if (conversations.selectOwned(conversationId, userId) == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        var user = users.findByUserId(userId);
        var permissions = PermissionCodes.of(user);
        if (user == null || (!permissions.contains("*") && !permissions.contains("ai:assistant:use"))) {
            throw new IllegalStateException("当前用户无 AI 使用权限");
        }
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        return execute(userId, conversationId, input, runId);
    }

    /** 仅供可信服务端使用，校验会话归属并隔离用户与会话检查点。 */
    Flux<NodeOutput> streamForUser(long userId, long conversationId, String input) throws GraphRunnerException {
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("消息不能为空");
        }
        return execute(userId, conversationId, input, UUID.randomUUID().toString());
    }

    /** 在会话锁内验证身份，并启动新消息或恢复追问。 */
    private Flux<NodeOutput> execute(long userId, long conversationId, String input, String requestedRunId) {
        String businessDate = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        var config = RunnableConfig.builder().threadId("main:" + userId + ":" + conversationId)
                .addMetadata("businessDate", businessDate)
                .addMetadata(TrustedToolContextInterceptor.TRUSTED_CONTEXT, new SqlToolContext(userId, conversationId, businessDate))
                .build();
        return Flux.defer(() -> {
            RLock lock = locks.of(conversationId);
            long owner = LOCK_OWNER.incrementAndGet();
            return Flux.create(sink -> lock.tryLockAsync(owner).whenCompleteAsync((acquired, failure) -> {
                if (failure != null) { sink.error(failure); return; }
                if (!acquired) { sink.error(new IllegalStateException("会话正在执行其他任务")); return; }
                if (sink.isCancelled()) {
                    lock.unlockAsync(owner).whenComplete((ignored, error) -> {
                        if (error != null) {
                            log.error("主智能体取消订阅后释放会话锁失败 conversationId={}", conversationId, error);
                        }
                    });
                    return;
                }
                try {
                    // 锁内检查，避免会话删除或权限撤销与启动并发。
                    if (conversations.selectOwned(conversationId, userId) == null) {
                        throw new IllegalArgumentException("会话不存在或无权访问");
                    }
                    var user = users.findByUserId(userId);
                    var permissions = PermissionCodes.of(user);
                    if (user == null || (!permissions.contains("*") && !permissions.contains("ai:assistant:use"))) {
                        throw new IllegalStateException("当前用户无 AI 使用权限");
                    }
                    var checkpoint = saver.get(config);
                    if (checkpoint.isPresent() && "_AGENT_TOOL_".equals(checkpoint.get().getNextNodeId())) {
                        var current = checkpoint.get();
                        if (hasPendingToolCalls(current)) {
                            var messages = (List<?>) current.getState().get("messages");
                            var assistant = (AssistantMessage) messages.getLast();
                            var responses = assistant.getToolCalls().stream().map(call ->
                                    new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                                            "{\"status\":\"INTERRUPTED\",\"message\":\"上次工具调用中断，未取得可确认的结果，请结合新消息判断下一步。\"}"))
                                    .toList();
                            var result = ToolResponseMessage.builder().responses(responses).build();
                            // 先写入原调用的中断结果，避免新消息之前缺少工具响应。
                            agent.getAndCompileGraph().updateState(
                                    RunnableConfig.builder(config).checkPointId(current.getId()).build(),
                                    Map.of("messages", List.of(result)));
                        } else if (!hasInterruptedToolResult(current)) {
                            throw new IllegalStateException("会话存在未完成的其他工具调用，请先确认执行状态");
                        }
                    }
                    var recorder = new AgentRunRecorder(messageStore, mapper, userId, conversationId,
                            requestedRunId);
                    recorder.start(input);
                    String runKey = userId + ":" + conversationId;
                    String runId = recorder.runId();
                    runs.put(runKey, new RuntimeSnapshot(runId, "RUNNING", null));
                    sink.next(NodeOutput.of("run_started", "main_agent", new com.alibaba.cloud.ai.graph.OverAllState(
                            Map.of("runId", runId, "conversationId", String.valueOf(conversationId))), null));
                    subscribeRun(config, recorder, runKey, runId, input, lock, owner, sink);
                } catch (Exception error) {
                    finish(lock, owner, sink, error);
                }
            }));
        });
    }

    /** 订阅内部运行流，持久化完整消息并维护本轮状态。 */
    private void subscribeRun(RunnableConfig config, AgentRunRecorder recorder, String runKey, String runId,
                              String input, RLock lock, long owner, FluxSink<NodeOutput> sink) throws Exception {
        var events = new RuntimeEventForwarder(sink::next, runId);
        var runConfig = RunnableConfig.builder(config)
                .addMetadata("runId", runId)
                .addMetadata(com.qiheng.erp.ai.agent.interceptor.ToolJsonInterceptor.ERROR_COUNT, new java.util.concurrent.atomic.AtomicInteger())
                .addMetadata("messageRecorder", recorder)
                .addMetadata("toolEvents", (java.util.function.Consumer<NodeOutput>) event -> {
                    if ("tool_finished".equals(event.node())) {
                        var data = event.state().data();
                        recorder.toolFinished((String) data.get("toolCallId"), (String) data.get("label"),
                                ((Number) data.get("durationMs")).longValue());
                    }
                    events.toolEvent(event);
                })
                .addMetadata("recordToolCall", (java.util.function.Consumer<com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest>) request -> {
                    if ("ask_user".equals(request.getToolName())) {
                        events.questionCall(request.getToolCallId());
                    }
                    var checkpoint = saver.get(config);
                    var messages = checkpoint.map(value -> value.getState().get("messages")).orElse(null);
                    AssistantMessage assistant = messages instanceof List<?> list && !list.isEmpty()
                            && list.getLast() instanceof AssistantMessage message ? message : null;
                    if (assistant != null && assistant.getToolCalls().stream()
                            .noneMatch(call -> request.getToolCallId().equals(call.id()))) {
                        assistant = null;
                    }
                    recorder.toolCall(request.getToolCallId(), request.getToolName(), request.getArguments(), assistant);
                })
                .addMetadata(ClarificationService.EVENT_KEY, (java.util.function.Consumer<NodeOutput>) event -> {
                    recorder.status("WAITING_USER", null);
                    var question = events.questionEvent(event);
                    runs.put(runKey, new RuntimeSnapshot(runId, "WAITING_USER", Map.copyOf(question.state().data())));
                    events.toolEvent(question);
                })
                .build();
        // 新消息交由模型结合已配对的工具历史处理。
        reactor.core.publisher.Flux<NodeOutput> stream;
        try {
            stream = agent.stream(input, runConfig);
        } catch (Exception error) {
            recorder.status("FAILED", "AGENT_EXECUTION_FAILED");
            runs.put(runKey, new RuntimeSnapshot(runId, "FAILED", null));
            throw error;
        }
        var reachedEnd = new java.util.concurrent.atomic.AtomicBoolean();
        stream.doOnNext(output -> {
                    // 节点完成后提取完整消息，流式片段只用于实时展示。
                    if (output.isEND() || output instanceof StreamingOutput<?> streaming
                            && streaming.message() instanceof ToolResponseMessage) {
                        if (output.isEND()) {
                            recorder.capture(output);
                            reachedEnd.set(true);
                        }
                        if (output instanceof StreamingOutput<?> streaming
                                && streaming.message() instanceof ToolResponseMessage response
                                && response.getResponses().stream().anyMatch(item -> "ask_user".equals(item.name()))) {
                            runs.put(runKey, new RuntimeSnapshot(runId, "RUNNING", null));
                        }
                    }
                }).subscribeOn(Schedulers.boundedElastic())
                .subscribe(events::graphEvent, error -> {
                    try {
                        events.flush();
                        recorder.status("FAILED", "AGENT_EXECUTION_FAILED");
                    } catch (Exception statusError) {
                        log.error("主智能体失败状态写入失败 runId={}", runId, statusError);
                    } finally {
                        runs.put(runKey, new RuntimeSnapshot(runId, "FAILED", null));
                        finish(lock, owner, sink, error);
                    }
                }, () -> {
                    try {
                        events.flush();
                        if (!reachedEnd.get()) {
                            recorder.status("FAILED", "AGENT_STREAM_INCOMPLETE");
                            runs.put(runKey, new RuntimeSnapshot(runId, "FAILED", null));
                            finish(lock, owner, sink, new IllegalStateException("主智能体运行流未到达结束节点"));
                            return;
                        }
                        recorder.status("COMPLETED", null);
                        runs.put(runKey, new RuntimeSnapshot(runId, "COMPLETED", null));
                        finish(lock, owner, sink, null);
                    } catch (Exception error) {
                        runs.put(runKey, new RuntimeSnapshot(runId, "FAILED", null));
                        finish(lock, owner, sink, error);
                    }
                });
    }

    /** 在图的模型完成事件发出后，再交付对应的工具状态与追问。 */
    static final class RuntimeEventForwarder {
        private final java.util.function.Consumer<NodeOutput> target;
        private final java.util.Set<String> ready = new java.util.HashSet<>();
        private final java.util.Set<String> degraded = new java.util.HashSet<>();
        private final java.util.List<Map.Entry<String, NodeOutput>> pending = new java.util.ArrayList<>();
        private final String runId;
        private final long waitMillis;
        private reactor.core.Disposable timer;
        private long timerGeneration;
        private String questionCallId;
        private boolean closed;
        private boolean awaitingLateCompletion;

        RuntimeEventForwarder(java.util.function.Consumer<NodeOutput> target) { this(target, "test", 1000); }
        RuntimeEventForwarder(java.util.function.Consumer<NodeOutput> target, String runId) { this(target, runId, 1000); }
        RuntimeEventForwarder(java.util.function.Consumer<NodeOutput> target, String runId, long waitMillis) {
            this.target = target; this.runId = runId; this.waitMillis = waitMillis;
        }

        synchronized void questionCall(String id) { questionCallId = id; }

        /** 为追问事件补充真实工具调用标识，实时展示与重连快照使用相同标识。 */
        synchronized NodeOutput questionEvent(NodeOutput event) {
            if (questionCallId == null) {
                return event;
            }
            var data = new java.util.LinkedHashMap<String, Object>(event.state().data());
            data.put("toolCallId", questionCallId);
            return NodeOutput.of(event.node(), "main_agent", new com.alibaba.cloud.ai.graph.OverAllState(data), null);
        }

        synchronized void toolEvent(NodeOutput event) {
            if (closed) {
                return;
            }
            Object raw = event.state().data().get("toolCallId");
            String id = raw instanceof String value ? value : questionCallId;
            if (id == null || ready.contains(id) && pending.isEmpty()) {
                target.accept(event);
                return;
            }
            pending.add(Map.entry(id, event));
            if (timer == null) {
                long generation = ++timerGeneration;
                timer = Schedulers.parallel().schedule(() -> releaseDelayed(generation), waitMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
            }
        }

        synchronized void graphEvent(NodeOutput event) {
            if (closed) {
                return;
            }
            // 结束事件本身就标志追问已失效，先取消计时器，避免结束后补发过期追问。
            if (event.isEND()) {
                flush();
                target.accept(event);
                return;
            }
            if (event instanceof StreamingOutput<?> streaming && streaming.message() instanceof ToolResponseMessage response) {
                var finished = response.getResponses().stream().map(ToolResponseMessage.ToolResponse::id).collect(java.util.stream.Collectors.toSet());
                // 工具已有响应时，尚未送达的追问已经无法再回答。
                boolean expiredQuestion = pending.stream().anyMatch(item -> "ask_user".equals(item.getValue().node()) && finished.contains(item.getKey()));
                if (expiredQuestion) {
                    pending.stream().filter(item -> "ask_user".equals(item.getValue().node()) && finished.contains(item.getKey()))
                            .forEach(item -> { ready.add(item.getKey()); degraded.add(item.getKey()); });
                    target.accept(NodeOutput.of("model_tool_fallback", "main_agent", new com.alibaba.cloud.ai.graph.OverAllState(Map.of()), null));
                }
                pending.removeIf(item -> "ask_user".equals(item.getValue().node()) && finished.contains(item.getKey()));
                if (!pending.isEmpty()) { cancelTimer(); releaseDelayed(expiredQuestion); } else {
                    cancelTimer();
                }
                // 同一图流中，工具响应之后才会进入下一轮模型。
                awaitingLateCompletion = false;
            }
            String node = event.node();
            if (node == null || !node.endsWith("message_recording.afterModel")) { target.accept(event); return; }
            Object value = event.state().data().get("messages");
            if (value instanceof List<?> list && !list.isEmpty()
                    && list.getLast() instanceof AssistantMessage assistant) {
                // 超时后已经切段；迟到的完整消息不能覆盖新的文字段。
                boolean late = !assistant.getToolCalls().isEmpty() && (awaitingLateCompletion
                        || assistant.getToolCalls().stream().anyMatch(call -> degraded.contains(call.id())));
                // 有待交付事件但没有一个调用 ID 能对应时，不能将该消息校正到当前文字段。
                boolean mismatch = !pending.isEmpty() && !assistant.getToolCalls().isEmpty()
                        && assistant.getToolCalls().stream().noneMatch(call -> pending.stream()
                        .anyMatch(item -> call.id().equals(item.getKey())));
                if (!assistant.getToolCalls().isEmpty()) {
                    awaitingLateCompletion = false;
                }
                if (!late && !mismatch) {
                    target.accept(event);
                    assistant.getToolCalls().forEach(call -> ready.add(call.id()));
                }
                if (mismatch) {
                    log.warn("模型完成事件与等待中的工具调用不匹配 runId={}", runId);
                }
                drain();
            } else {
                target.accept(event);
            }
        }

        private void drain() {
            while (!pending.isEmpty() && ready.contains(pending.getFirst().getKey())) {
                target.accept(pending.removeFirst().getValue());
            }
            if (pending.isEmpty()) {
                cancelTimer();
            }
        }

        /** 等待排序超时后先切段，再放行事件，保证追问可见。 */
        private synchronized void releaseDelayed(long generation) {
            if (closed || generation != timerGeneration) {
                return;
            }
            releaseDelayed(false);
        }

        private void releaseDelayed(boolean boundarySent) {
            timer = null;
            while (!pending.isEmpty()) {
                String id = pending.getFirst().getKey();
                if (ready.add(id)) {
                    degraded.add(id);
                    log.warn("模型完成事件未及时匹配，已放行工具事件 runId={} toolCallId={}", runId, id);
                    if (!boundarySent) {
                        target.accept(NodeOutput.of("model_tool_fallback", "main_agent", new com.alibaba.cloud.ai.graph.OverAllState(Map.of()), null));
                        boundarySent = true;
                        awaitingLateCompletion = true;
                    }
                }
                target.accept(pending.removeFirst().getValue());
            }
        }

        private void cancelTimer() {
            timerGeneration++;
            if (timer != null) { timer.dispose(); timer = null; }
        }

        synchronized void flush() {
            if (closed) {
                return;
            }
            closed = true;
            cancelTimer();
            // 结束时追问已无法再接收回答，不向页面补发过期问题。
            pending.removeIf(item -> "ask_user".equals(item.getValue().node()));
            // 普通工具可能已完成但未等到模型事件；结束前仍需先分段。
            releaseDelayed(false);
        }
    }

    /** 只处理最后一条尚未获得结果的工具调用消息。 */
    private boolean hasPendingToolCalls(com.alibaba.cloud.ai.graph.checkpoint.Checkpoint checkpoint) {
        var messages = (List<?>) checkpoint.getState().get("messages");
        return "_AGENT_TOOL_".equals(checkpoint.getNextNodeId()) && messages != null && !messages.isEmpty()
                && messages.getLast() instanceof AssistantMessage last && !last.getToolCalls().isEmpty();
    }

    /** 补写结果后若启动失败，下次继续不重复追加同一工具结果。 */
    private boolean hasInterruptedToolResult(com.alibaba.cloud.ai.graph.checkpoint.Checkpoint checkpoint) {
        var messages = (List<?>) checkpoint.getState().get("messages");
        if (messages == null || messages.size() < 2
                || !(messages.getLast() instanceof ToolResponseMessage response)
                || !(messages.get(messages.size() - 2) instanceof AssistantMessage assistant)
                || assistant.getToolCalls().isEmpty()) {
            return false;
        }
        return response.getResponses().size() == assistant.getToolCalls().size()
                && assistant.getToolCalls().stream().allMatch(call -> response.getResponses().stream().anyMatch(item ->
                call.id().equals(item.id()) && call.name().equals(item.name())
                        && item.responseData().contains("\"status\":\"INTERRUPTED\"")));
    }

    /** 在内部流真正结束后释放锁，再通知外部订阅者。 */
    private void finish(RLock lock, long owner, FluxSink<NodeOutput> sink, Throwable error) {
        lock.unlockAsync(owner).whenComplete((ignored, unlockFailure) -> {
            if (unlockFailure != null) {
                log.error("主智能体执行结束后释放会话锁失败", unlockFailure);
            }
            if (error != null) {
                sink.error(error);
            } else if (unlockFailure != null) {
                sink.error(unlockFailure);
            } else {
                sink.complete();
            }
        });
    }
}
