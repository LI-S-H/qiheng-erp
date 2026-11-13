package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import com.qiheng.erp.ai.trace.AiAgentTraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 按图状态顺序保存本轮已形成的消息，不将流式分片重复写入。
 *
 * <p>消息表保存前端要渲染的内容，智能体流水保存本次执行的原文与调用过程；
 * 两者内容不同、用途不同，各自写入。</p>
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
        save("input", "user", "USER_INPUT", true, input, "RUNNING");
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
                save("result:" + item.id(), "tool", "TOOL_RESULT", "ask_user".equals(item.name()),
                        Map.of("callId", item.id(), "name", item.name(), "result", item.responseData()), null);
                if ("ask_user".equals(item.name())) {
                    status("RUNNING", null);
                }
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("callId", item.id());
                content.put("name", item.name());
                content.put("text", item.responseData());
                traces.record("TOOL_RESULT", content);
            }
        }
    }

    /** 工具执行前保存调用，并以调用 ID 作为稳定标识。 */
    synchronized void toolCall(String id, String name, String arguments, AssistantMessage assistant) {
        if (assistant != null && assistant.getText() != null && !assistant.getText().isBlank()) {
            String firstId = assistant.getToolCalls().getFirst().id();
            save("step:" + firstId, "assistant", "ASSISTANT_STEP", true,
                    assistant.getText(), null);
            recordModelOutput(firstId, assistant.getText());
        }
        save("call:" + id, "assistant", "TOOL_CALL", "ask_user".equals(name),
                Map.of("callId", id, "name", name, "arguments", arguments), null);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("callId", id);
        content.put("name", name);
        content.put("text", arguments);
        traces.record("TOOL_CALL", content);
    }

    /** 保存可展示的工具耗时摘要，不记录参数或查询结果。 */
    synchronized void toolFinished(String id, String label, long durationMs) {
        String duration = String.format(Locale.ROOT, "%.1f", durationMs / 1000.0) + " 秒";
        save("tool-summary:" + id, "assistant", "ASSISTANT_STEP", true,
                label.replaceFirst("^正在", "") + " · " + duration, null);
    }

    /** 保存分析子智能体的完整过程；内容与实时事件一致，供回看时重建分析面板。 */
    synchronized void analysis(Map<String, Object> data) {
        try {
            save("analysis:" + data.get("toolCallId"), "assistant", "ANALYSIS_STEP", true, data, null);
        } catch (RuntimeException error) {
            // 展示行写入失败不能吞掉已经算好的结论，与流水表保持同样的降级策略。
            log.error("分析过程写入失败 runId={} toolCallId={} errorType={}", runId, data.get("toolCallId"),
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
        save("final", "assistant", "ASSISTANT_FINAL", true, assistant.getText(), null);
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
    private void save(String key, String role, String kind, boolean visible, Object content, String status) {
        try {
            store.append(new AiMessage().setUserId(userId).setConversationId(conversationId).setRequestId(runId)
                    .setMessageKey(runId + ":" + key).setRole(role).setKind(kind).setVisible(visible)
                    .setContent(mapper.writeValueAsString(content)).setRunStatus(status));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("消息序列化失败", error);
        }
    }
}
