package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.clarification.ClarificationRules;
import com.qiheng.erp.ai.clarification.ClarificationTexts;
import com.qiheng.erp.ai.clarification.ClarificationTraceSink;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HITL 之前的追问参数校验。
 *
 * <p>本 Hook 的 order 大于 HITL，按 AFTER_MODEL 倒序执行，因此先于 HITL 运行。
 * 参数非法时追加一条错误 ToolResponseMessage，让图直接回到模型节点重试；
 * 连续失败达到上限后追加最终说明并跳转 END。</p>
 *
 * @author Li
 * @since 2026-11-26
 */
@HookPositions({HookPosition.AFTER_MODEL})
public final class AskUserValidationHook extends ModelHook {

    /** 追问工具名称。 */
    public static final String TOOL_NAME = "ask_user";

    /** 单次运行允许的连续非法次数，超过后终止。 */
    public static final int MAX_ATTEMPTS = 3;

    /** 终止时写入图状态的标记，供事件转换层发出失败事件。 */
    public static final String FAILED_STATE_KEY = "ask_user_validation_failed";

    /** 本次非法调用的编号，供事件转换层抑制该次 assistant 文字。 */
    public static final String INVALID_CALL_KEY = "ask_user_invalid_call_id";

    private static final String RECORDER_METADATA = "messageRecorder";
    private static final String RUN_ID_METADATA = "runId";
    private static final String FAILURE_STATUS = "invalid_arguments";

    private final ObjectMapper mapper;
    private final ConcurrentMap<String, AtomicInteger> failuresByRun = new ConcurrentHashMap<>();

    /**
     * 注入 JSON 解析器。
     *
     * @param mapper JSON 解析器
     */
    public AskUserValidationHook(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
        String runId = runId(config);
        Optional<AssistantMessage.ToolCall> askCall = findAskUserCall(state);
        if (askCall.isEmpty()) {
            failuresByRun.remove(runId);
            return CompletableFuture.completedFuture(Map.of());
        }
        AssistantMessage.ToolCall call = askCall.get();
        ClarificationRules.QuestionValidation validation =
                ClarificationRules.validateQuestions(call.arguments(), mapper);
        if (validation.valid()) {
            failuresByRun.remove(runId);
            return CompletableFuture.completedFuture(Map.of());
        }

        int attempt = failuresByRun.computeIfAbsent(runId, ignored -> new AtomicInteger()).incrementAndGet();
        String errorJson = errorJson(validation.errors());
        ClarificationTraceSink sink = traceSink(config);
        if (sink != null) {
            sink.traceAskUserValidationFailure(call.id(), call.name(), call.arguments(), validation.errors());
        }
        ToolResponseMessage response = toolResponse(call, errorJson);
        if (attempt <= MAX_ATTEMPTS) {
            return CompletableFuture.completedFuture(Map.of(
                    "messages", List.of(response),
                    INVALID_CALL_KEY, call.id()));
        }

        // 终止前补齐结果流水；下一次模型调用不存在，beforeModel 不会再补。
        if (sink != null) {
            sink.traceAskUserToolResult(call.id(), call.name(), errorJson);
            sink.markClarificationValidationFailed();
        }
        failuresByRun.remove(runId);
        AssistantMessage finalMessage = new AssistantMessage(terminalMessage(call.arguments()));
        return CompletableFuture.completedFuture(Map.of(
                "messages", List.of(response, finalMessage),
                "jump_to", JumpTo.end,
                FAILED_STATE_KEY, runId,
                INVALID_CALL_KEY, call.id()));
    }

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public String getName() {
        return "ask_user_validation";
    }

    /** 允许终止时跳转 END，非法重试仍走默认模型路由。 */
    @Override
    public List<JumpTo> canJumpTo() {
        return List.of(JumpTo.model, JumpTo.end);
    }

    /** 终止标记需要写入图状态，供事件转换层识别。 */
    @Override
    public Map<String, KeyStrategy> getKeyStrategys() {
        return Map.of(
                FAILED_STATE_KEY, new ReplaceStrategy(),
                INVALID_CALL_KEY, new ReplaceStrategy());
    }

    /** 找到最后一条 AssistantMessage 里的 ask_user 调用。 */
    @SuppressWarnings("unchecked")
    private Optional<AssistantMessage.ToolCall> findAskUserCall(OverAllState state) {
        Object raw = state.value("messages").orElse(null);
        if (!(raw instanceof List<?> messages) || messages.isEmpty()
                || !(messages.getLast() instanceof AssistantMessage assistant)) {
            return Optional.empty();
        }
        return assistant.getToolCalls().stream()
                .filter(call -> TOOL_NAME.equals(call.name()))
                .findFirst();
    }

    /** 终止文案：优先把错误 JSON 里能识别的问题按顺序贴给用户。 */
    private String terminalMessage(String arguments) {
        List<String> lines = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(arguments);
            JsonNode nodes = root == null ? null : root.path("questions");
            if (nodes != null && nodes.isArray()) {
                for (JsonNode node : nodes) {
                    String text = node.path("question").asText("").replaceAll("\\s+", " ").trim();
                    if (text.isBlank()) {
                        text = node.path("id").asText("").replaceAll("\\s+", " ").trim();
                    }
                    if (!text.isBlank()) {
                        lines.add("- 问题" + (lines.size() + 1) + "：" + text);
                    }
                }
            }
        } catch (JsonProcessingException ignored) {
            // 参数本身已经非法，能解析多少就贴多少；解析失败退回通用说明。
        }
        if (lines.isEmpty()) {
            return ClarificationTexts.TERMINAL_FAILURE_MESSAGE;
        }
        return ClarificationTexts.TERMINAL_ERROR_HEADER + "\n" + String.join("\n", lines);
    }

    /** 组装给模型看的字段级错误 JSON。 */
    private String errorJson(List<ClarificationRules.FieldError> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", FAILURE_STATUS);
        body.put("message", "追问参数校验失败，请按 errors 修正后重新调用 ask_user。");
        body.put("errors", errors.stream()
                .map(error -> Map.of("path", error.path(), "message", error.message()))
                .toList());
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException error) {
            return "{\"status\":\"" + FAILURE_STATUS + "\",\"message\":\"追问参数校验失败\"}";
        }
    }

    /** 构造与调用编号配对的工具结果。 */
    private ToolResponseMessage toolResponse(AssistantMessage.ToolCall call, String errorJson) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(), errorJson)))
                .build();
    }

    /** 取本次运行的流水写入入口；测试或旧配置下可能为空。 */
    private ClarificationTraceSink traceSink(RunnableConfig config) {
        Object value = config.metadata(RECORDER_METADATA).orElse(null);
        return value instanceof ClarificationTraceSink sink ? sink : null;
    }

    /** 计数按单次运行隔离；缺少 runId 时退回线程编号。 */
    private String runId(RunnableConfig config) {
        return config.metadata(RUN_ID_METADATA)
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .orElseGet(() -> config.threadId().orElse("unknown"));
    }
}