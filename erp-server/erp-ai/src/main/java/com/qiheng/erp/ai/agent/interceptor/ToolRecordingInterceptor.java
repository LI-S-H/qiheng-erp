package com.qiheng.erp.ai.agent.interceptor;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.qiheng.erp.ai.agent.AgentRunKeys;
import com.qiheng.erp.ai.constant.AiToolLabels;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 在工具执行前后发布工具状态，供页面展示运行过程。
 *
 * @author Li
 * @since 2026-10-27
 */
public final class ToolRecordingInterceptor extends ToolInterceptor {

    /**
     * 发布工具开始与结束状态，并记录本次调用的耗时。
     *
     * @param request 本次工具调用请求
     * @param handler 后续拦截器与工具的执行链
     * @return 工具执行结果
     */
    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        request.getExecutionContext().flatMap(context -> context.config().metadata(AgentRunKeys.RECORD_TOOL_CALL))
                .ifPresent(value -> ((Consumer<ToolCallRequest>) value).accept(request));
        var callback = request.getExecutionContext().flatMap(context -> context.config().metadata(AgentRunKeys.TOOL_EVENTS));
        // 追问由专用事件通道处理，不在工具过程里重复展示。
        if (callback.isEmpty() || "ask_user".equals(request.getToolName())) {
            return handler.call(request);
        }
        var emit = (Consumer<NodeOutput>) callback.get();
        String label = AiToolLabels.runningLabel(request.getToolName());
        emit.accept(NodeOutput.of("tool_started", "main_agent",
                new OverAllState(Map.of("toolCallId", request.getToolCallId(),
                        "tool", request.getToolName(), "label", label)), null));
        long started = System.nanoTime();
        String status = "returned";
        // 完成时回到基础文案，页面据此展示“已完成”的工具名。
        String finalLabel = AiToolLabels.label(request.getToolName());
        try {
            var response = handler.call(request);
            if (ToolJsonInterceptor.ERROR_MESSAGE.equals(response.getResult())) {
                status = "invalid_arguments";
                finalLabel = "参数错误，正在调整";
            }
            return response;
        } catch (RuntimeException error) {
            status = "failed";
            if (error instanceof ToolJsonInterceptor.ArgumentsExhaustedException) {
                finalLabel = "参数错误，已停止";
            }
            throw error;
        } finally {
            emit.accept(NodeOutput.of("tool_finished", "main_agent",
                    new OverAllState(Map.of("toolCallId", request.getToolCallId(), "status", status,
                            "label", finalLabel, "durationMs", (System.nanoTime() - started) / 1_000_000)), null));
        }
    }

    /** 提供拦截器在主智能体中的名称。 */
    @Override
    public String getName() {
        return "tool_recording";
    }
}
