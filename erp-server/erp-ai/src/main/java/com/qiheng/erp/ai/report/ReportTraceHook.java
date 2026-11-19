package com.qiheng.erp.ai.report;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.qiheng.erp.ai.trace.AiAgentTraceRecorder;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 记录报告专家的模型答复和工具交互；不改变模型调用流程。 */
@HookPositions({HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL})
final class ReportTraceHook extends MessagesModelHook {
    static final String RECORDER_KEY = "reportTraceRecorder";

    /** 将本轮连续返回的工具结果分别记录，保留调用 ID。 */
    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        AiAgentTraceRecorder recorder = recorder(config);
        if (recorder == null) {
            return new AgentCommand(null);
        }
        int start = messages.size();
        while (start > 0 && messages.get(start - 1) instanceof ToolResponseMessage) {
            start--;
        }
        for (int i = start; i < messages.size(); i++) {
            var response = (ToolResponseMessage) messages.get(i);
            for (var item : response.getResponses()) {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("callId", item.id());
                content.put("name", item.name());
                content.put("text", item.responseData());
                recorder.record("TOOL_RESULT", content);
            }
        }
        return new AgentCommand(null);
    }

    /** 格式错误的最终答复也记录，供后续还原修正过程。 */
    @Override
    public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
        AiAgentTraceRecorder recorder = recorder(config);
        if (recorder == null || messages.isEmpty()
                || !(messages.getLast() instanceof AssistantMessage assistant)) {
            return new AgentCommand(null);
        }
        if (assistant.getText() != null && !assistant.getText().isBlank()) {
            recorder.recordText("MODEL_OUTPUT", assistant.getText());
        }
        for (var call : assistant.getToolCalls()) {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("callId", call.id());
            content.put("name", call.name());
            content.put("text", call.arguments());
            recorder.record("TOOL_CALL", content);
        }
        return new AgentCommand(null);
    }

    /** 独立入口无需配置持久化记录器。 */
    static AiAgentTraceRecorder recorder(RunnableConfig config) {
        return (AiAgentTraceRecorder) config.metadata(RECORDER_KEY).orElse(null);
    }

    @Override
    public String getName() {
        return "report_trace";
    }
}
