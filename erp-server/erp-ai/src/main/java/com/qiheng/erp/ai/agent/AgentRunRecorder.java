package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Map;

/** 按图状态顺序保存本轮已形成的消息，不将流式分片重复写入。 */
final class AgentRunRecorder {
    private final ConversationMessageStore store;
    private final ObjectMapper mapper;
    private final long userId;
    private final long conversationId;
    private final String runId;

    AgentRunRecorder(ConversationMessageStore store, ObjectMapper mapper, long userId, long conversationId,
                     String runId) {
        this.store = store;
        this.mapper = mapper;
        this.userId = userId;
        this.conversationId = conversationId;
        this.runId = runId;
    }

    /** 保存用户输入并创建本轮状态。 */
    void start(String input) {
        save("input", "user", "USER_INPUT", true, input, "RUNNING");
        store.nameFirstInput(userId, conversationId, input);
    }

    String runId() { return runId; }

    /** 保存模型调用前最后一条消息；用户输入在任务开始时已保存。 */
    synchronized void beforeModel(List<? extends org.springframework.ai.chat.messages.Message> messages) {
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
            }
        }
    }

    /** 工具执行前保存调用，并以调用 ID 作为稳定标识。 */
    synchronized void toolCall(String id, String name, String arguments, AssistantMessage assistant) {
        if (assistant != null && assistant.getText() != null && !assistant.getText().isBlank()) {
            String firstId = assistant.getToolCalls().getFirst().id();
            save("step:" + firstId, "assistant", "ASSISTANT_STEP", true,
                    assistant.getText(), null);
        }
        save("call:" + id, "assistant", "TOOL_CALL", "ask_user".equals(name),
                Map.of("callId", id, "name", name, "arguments", arguments), null);
    }

    /** 保存可展示的工具耗时摘要，不记录参数或查询结果。 */
    synchronized void toolFinished(String id, String label, long durationMs) {
        save("tool-summary:" + id, "assistant", "ASSISTANT_STEP", true,
                label.replaceFirst("^正在", "") + " · " + String.format(java.util.Locale.ROOT, "%.1f", durationMs / 1000.0) + " 秒", null);
    }

    /** 图结束后保存最终回复；该回复之后不会再进入 beforeModel。 */
    synchronized void capture(NodeOutput output) {
        var raw = output.state().data().get("messages");
        if (!(raw instanceof List<?> messages) || messages.isEmpty()) {
            return;
        }
        if (output.isEND() && messages.getLast() instanceof AssistantMessage assistant
                && assistant.getToolCalls().isEmpty() && assistant.getText() != null && !assistant.getText().isBlank()) {
            save("final", "assistant", "ASSISTANT_FINAL", true,
                    assistant.getText(), null);
        }
    }

    /** 更新用户输入行上的本轮状态。 */
    void status(String status, String errorCode) {
        store.updateRunStatus(userId, conversationId, runId, status, errorCode);
    }

    private void save(String key, String role, String kind, boolean visible, Object content, String status) {
        try {
            store.append(new AiMessage().setUserId(userId).setConversationId(conversationId).setRequestId(runId)
                    .setMessageKey(runId + ":" + key).setRole(role).setKind(kind).setVisible(visible)
                    .setContent(mapper.writeValueAsString(content)).setRunStatus(status));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("消息序列化失败", error);
        }
    }
}
