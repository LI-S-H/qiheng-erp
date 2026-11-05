package com.qiheng.erp.ai.agent.interceptor;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import java.util.function.Consumer;
import java.util.Map;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;

/** 在执行工具前保存模型生成的调用。 */
public final class ToolRecordingInterceptor extends ToolInterceptor {
    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        request.getExecutionContext().flatMap(context -> context.config().metadata("recordToolCall"))
                .ifPresent(value -> ((Consumer<ToolCallRequest>) value).accept(request));
        var callback = request.getExecutionContext().flatMap(context -> context.config().metadata("toolEvents"));
        if (callback.isEmpty() || "ask_user".equals(request.getToolName())) {
            return handler.call(request);
        }
        var emit = (Consumer<NodeOutput>) callback.get();
        String label = switch (request.getToolName()) {
            case "query_sql_data" -> "正在查询业务数据";
            case "read_dataset" -> "正在读取数据";
            default -> "正在处理当前步骤";
        };
        emit.accept(NodeOutput.of("tool_started", "main_agent", new OverAllState(Map.of(
                "toolCallId", request.getToolCallId(), "tool", request.getToolName(), "label", label)), null));
        long started = System.nanoTime();
        String status = "returned";
        String finalLabel = label;
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
            emit.accept(NodeOutput.of("tool_finished", "main_agent", new OverAllState(Map.of(
                    "toolCallId", request.getToolCallId(), "status", status, "label", finalLabel,
                    "durationMs", (System.nanoTime() - started) / 1_000_000)), null));
        }
    }
    @Override public String getName() { return "tool_recording"; }
}
