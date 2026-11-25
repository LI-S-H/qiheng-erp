package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.constant.AiToolLabels;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import com.qiheng.erp.ai.trace.AiAgentTraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 按图状态顺序保存本轮已形成的消息，不将流式分片重复写入。
 *
 * <p>这里的每一行都是前端要渲染的内容：模型思考文字是 {@code ASSISTANT_STEP}，
 * 工具执行是带耗时与状态的 {@code TOOL_CALL}，追问另有一行 {@code TOOL_RESULT} 承载回答。
 * 工具的参数与返回原文不在本类保存，由 {@link AiAgentTraceRecorder} 写入流水表。</p>
 *
 * @author Li
 * @since 2026-10-27
 */
final class AgentRunRecorder {
    private static final Logger log = LoggerFactory.getLogger(AgentRunRecorder.class);

    private final ConversationMessageStore store;
    private final ObjectMapper mapper;
    private final AiAgentTraceRecorder traces;
    private final long userId;
    private final long conversationId;
    private final String runId;

    /** 已记录过模型原文的助手消息标识，避免同一条消息按工具调用重复记录。 */
    private String recordedModelOutputKey;

    /** 本轮是否以模型调用失败结束；失败时不写最终答复，只记流水。 */
    private boolean modelFailed;

    /** 本轮各工具调用的名称；结束时据此补全展示行。 */
    private final Map<String, String> toolNames = new LinkedHashMap<>();

    /** 同一次工具调用的阶段累积在同一消息中。 */
    private final Map<String, Map<String, Object>> reportProcesses = new LinkedHashMap<>();
    private final Map<String, Long> reportSequences = new LinkedHashMap<>();

    AgentRunRecorder(ConversationMessageStore store, ObjectMapper mapper, AiAgentTraceRecorder traces,
                     long userId, long conversationId, String runId) {
        this.store = store;
        this.mapper = mapper;
        this.traces = traces;
        this.userId = userId;
        this.conversationId = conversationId;
        this.runId = runId;
    }

    /** 保存用户输入并创建本轮状态。 */
    void start(String input) {
        save("input", "user", "USER_INPUT", input, "RUNNING");
        store.nameFirstInput(userId, conversationId, input);
        traces.recordText("INPUT", input);
    }

    /** 取得本轮的运行标识；与数据库消息使用同一标识。 */
    String runId() {
        return runId;
    }

    /** 保存模型调用前最后一条消息；用户输入在任务开始时已保存。 */
    synchronized void beforeModel(List<? extends Message> messages) {
        if (messages.isEmpty()) {
            return;
        }
        if (messages.getLast() instanceof ToolResponseMessage response) {
            for (var item : response.getResponses()) {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("callId", item.id());
                content.put("name", item.name());
                content.put("text", item.responseData());
                traces.record("TOOL_RESULT", content);
                // 只有追问的回答需要展示；其他工具的返回只留在流水里。
                if (!"ask_user".equals(item.name())) {
                    continue;
                }
                save("result:" + item.id(), "tool", "TOOL_RESULT",
                        Map.of("callId", item.id(), "name", item.name(), "result", item.responseData()), null);
                status("RUNNING", null);
            }
        }
    }

    /** 只写工具调用流水，供未进入工具节点的待确认调用使用，避免重复写展示消息。 */
    synchronized void traceToolCall(String id, String name, String arguments) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("callId", id);
        content.put("name", name);
        content.put("text", arguments);
        traces.record("TOOL_CALL", content);
    }

    /** 人工确认前保存正文及其调用标识，方便历史消息与确认卡片准确配对。 */
    synchronized void assistantBeforeConfirmation(AssistantMessage assistant) {
        saveAssistantBeforeTool(assistant, true);
    }

    /** 普通工具和人工确认共用稳定键，恢复执行时由存储层去重。 */
    private void saveAssistantBeforeTool(AssistantMessage assistant, boolean confirmation) {
        if (assistant != null && assistant.getText() != null && !assistant.getText().isBlank()
                && !assistant.getToolCalls().isEmpty()) {
            String firstId = assistant.getToolCalls().getFirst().id();
            Object content = confirmation ? Map.of("text", assistant.getText(), "callId", firstId) : assistant.getText();
            save("step:" + firstId, "assistant", "ASSISTANT_STEP", content, null);
            recordModelOutput(firstId, assistant.getText());
        }
    }

    /** 工具执行前保存调用，并以调用 ID 作为稳定标识。 */
    synchronized void toolCall(String id, String name, String arguments, AssistantMessage assistant) {
        toolNames.put(id, name);
        // 模型正文必须先于工具展示行写入，顺序由 seq 保证。
        saveAssistantBeforeTool(assistant, false);
        // 追问要在开始时写，前端才能立刻显示问题；其他工具等结束时补耗时。
        if ("ask_user".equals(name)) {
            save("call:" + id, "assistant", "TOOL_CALL", askUserContent(id, name, arguments), null);
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("callId", id);
        content.put("name", name);
        content.put("text", arguments);
        traces.record("TOOL_CALL", content);
    }

    /**
     * 工具结束后写入展示行；一行包含工具名、文案、状态与耗时。
     *
     * @param id 工具调用编号
     * @param label 结束时的文案，可带失败说明
     * @param status 工具状态：returned / failed / invalid_arguments
     * @param durationMs 工具执行耗时，毫秒
     */
    synchronized void toolFinished(String id, String label, String status, long durationMs) {
        String name = toolNames.get(id);
        // 追问的展示行在调用开始时已写，这里不重复。
        if (name == null || "ask_user".equals(name)) {
            return;
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("callId", id);
        content.put("name", name);
        content.put("label", label == null ? AiToolLabels.label(name) : label.replaceFirst("^正在", ""));
        content.put("status", "failed".equals(status) ? "failed" : "done");
        content.put("durationMs", durationMs);
        save("tool:" + id, "assistant", "TOOL_CALL", content, null);
    }

    /** 追问调用行：问题内容供前端渲染追问卡片。 */
    private Map<String, Object> askUserContent(String id, String name, String arguments) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("callId", id);
        content.put("name", name);
        content.put("label", AiToolLabels.label(name));
        content.put("status", "done");
        content.put("arguments", arguments);
        return content;
    }

    /** 保存分析子智能体的完整过程；内容与实时事件一致，供回看时重建分析面板。 */
    synchronized void analysis(Map<String, Object> data) {
        try {
            save("analysis:" + data.get("toolCallId"), "assistant", "ANALYSIS_STEP", data, null);
        } catch (RuntimeException error) {
            // 展示行写入失败不能吞掉已经算好的结论，与流水表保持同样的降级策略。
            log.error("分析过程写入失败 runId={} toolCallId={} errorType={}", runId, data.get("toolCallId"),
                    error.getClass().getName());
        }
    }

    /** 增量保存报告过程；消息写入失败不影响实时事件或报告生成。 */
    @SuppressWarnings("unchecked")
    synchronized void report(String event, Map<String, Object> data) {
        try {
            String toolCallId = (String) data.get("toolCallId");
            long sequence = ((Number) data.get("stepSeq")).longValue();
            if (toolCallId == null || toolCallId.isBlank()
                    || sequence <= reportSequences.getOrDefault(toolCallId, 0L)) {
                return;
            }
            var process = reportProcesses.computeIfAbsent(toolCallId, ignored -> {
                var initial = new LinkedHashMap<String, Object>();
                initial.put("toolCallId", toolCallId);
                initial.put("status", "RUNNING");
                initial.put("steps", new ArrayList<Map<String, Object>>());
                return initial;
            });
            if (data.get("taskId") instanceof String taskId && !taskId.isBlank()) {
                process.put("taskId", taskId);
            }
            if ("report_ready".equals(event)) {
                process.put("status", "COMPLETED");
                process.put("report", Map.of("reportId", data.get("reportId"),
                        "requestId", data.get("requestId"), "toolCallId", toolCallId,
                        "title", data.get("title"), "placeholder", data.get("placeholder")));
            } else {
                var steps = (List<Map<String, Object>>) process.get("steps");
                steps.add(Map.of("phase", data.get("phase"), "text", data.get("text"), "stepSeq", sequence));
                if ("FAILED".equals(data.get("phase"))) {
                    process.put("status", "FAILED");
                }
            }
            reportSequences.put(toolCallId, sequence);
            var message = new AiMessage().setUserId(userId).setConversationId(conversationId).setRequestId(runId)
                    .setMessageKey(runId + ":report:" + toolCallId).setRole("assistant").setKind("REPORT_STEP")
                    .setContent(mapper.writeValueAsString(process));
            store.saveReportProcess(message);
        } catch (Exception error) {
            log.error("报告过程写入失败 runId={} toolCallId={} errorType={}", runId, data.get("toolCallId"),
                    error.getClass().getName());
        }
    }

    /** 图结束后保存最终回复；该回复之后不会再进入 beforeModel。 */
    synchronized void capture(NodeOutput output) {
        var raw = output.state().data().get("messages");
        if (!(raw instanceof List<?> messages) || messages.isEmpty()) {
            return;
        }
        if (!output.isEND() || !(messages.getLast() instanceof AssistantMessage assistant)
                || !assistant.getToolCalls().isEmpty()
                || assistant.getText() == null || assistant.getText().isBlank()) {
            return;
        }
        // 模型调用失败时框架返回错误文本，不能作为最终答复展示给用户，只留在流水里。
        if (AgentModelFailure.isFailure(assistant.getText())) {
            modelFailed = true;
            traces.recordText("MODEL_FAILURE", assistant.getText());
            return;
        }
        save("final", "assistant", "ASSISTANT_FINAL", assistant.getText(), null);
        traces.recordText("FINAL", assistant.getText());
    }

    /** 本轮是否以模型调用失败结束。 */
    boolean modelFailed() {
        return modelFailed;
    }

    /** 更新用户输入行上的本轮状态。 */
    void status(String status, String errorCode) {
        store.updateRunStatus(userId, conversationId, runId, status, errorCode);
    }

    /**
     * 记录模型这一轮的原话；同一条助手消息只记一次。
     *
     * @param messageKey 助手消息的标识
     * @param text 模型原话
     */
    private void recordModelOutput(String messageKey, String text) {
        if (messageKey.equals(recordedModelOutputKey)) {
            return;
        }
        recordedModelOutputKey = messageKey;
        traces.recordText("MODEL_OUTPUT", text);
    }

    /** 按运行内唯一键追加一条消息；键已存在时由存储层保证不重复写入。 */
    private void save(String key, String role, String kind, Object content, String status) {
        try {
            store.append(new AiMessage().setUserId(userId).setConversationId(conversationId).setRequestId(runId)
                    .setMessageKey(runId + ":" + key).setRole(role).setKind(kind)
                    .setContent(mapper.writeValueAsString(content)).setRunStatus(status));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("消息序列化失败", error);
        }
    }
}
