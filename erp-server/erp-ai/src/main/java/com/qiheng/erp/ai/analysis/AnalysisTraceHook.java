package com.qiheng.erp.ai.analysis;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.qiheng.erp.ai.constant.AiToolLabels;
import com.qiheng.erp.ai.trace.AiAgentTraceRecorder;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在模型与工具的消息边界记录完整消息，并把每轮思路与工具调用发布给前端。
 *
 * @author Li
 * @since 2026-11-10
 */
@HookPositions({HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL})
final class AnalysisTraceHook extends MessagesModelHook {
    static final String RECORDER_KEY = "analysisTraceRecorder";

    /**
     * 模型下一次调用前逐条记录框架生成的工具结果。
     *
     * @param messages 当前消息列表
     * @param config 本次运行的配置，携带流水记录器
     * @return 不改变图流程
     */
    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        var recorder = (AiAgentTraceRecorder) config.metadata(RECORDER_KEY).orElseThrow();
        if (!messages.isEmpty() && messages.getLast() instanceof ToolResponseMessage response) {
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

    /**
     * 工具执行前记录同一助手消息中的文字和每个工具调用，并发布本轮过程。
     *
     * @param messages 当前消息列表
     * @param config 本次运行的配置，携带流水记录器与过程通道
     * @return 不改变图流程
     */
    @Override
    public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
        var recorder = (AiAgentTraceRecorder) config.metadata(RECORDER_KEY).orElseThrow();
        var progress = (AnalysisProgress) config.metadata(AnalysisProgress.CONTEXT_KEY).orElse(null);
        if (!messages.isEmpty() && messages.getLast() instanceof AssistantMessage assistant
                && !assistant.getToolCalls().isEmpty()) {
            if (assistant.getText() != null && !assistant.getText().isBlank()) {
                recorder.recordText("MODEL_OUTPUT", assistant.getText());
            }
            var tools = new ArrayList<Map<String, Object>>();
            for (var call : assistant.getToolCalls()) {
                Map<String, Object> content = new LinkedHashMap<>();
                content.put("callId", call.id());
                content.put("name", call.name());
                content.put("text", call.arguments());
                recorder.record("TOOL_CALL", content);
                var tool = new LinkedHashMap<String, Object>();
                tool.put("name", call.name());
                tool.put("label", AiToolLabels.label(call.name()));
                tools.add(tool);
            }
            // 文字与工具来自同一条助手消息，先于工具执行发布，顺序天然正确。
            if (progress != null) {
                config.metadata(AnalysisTaskContext.CONTEXT_KEY)
                        .ifPresent(value -> ((AnalysisTaskContext) value).touch());
                progress.step(assistant.getText(), tools);
            }
        }
        return new AgentCommand(null);
    }

    /** 供框架识别本任务的流水 Hook。 */
    @Override
    public String getName() {
        return "analysis_trace";
    }
}
