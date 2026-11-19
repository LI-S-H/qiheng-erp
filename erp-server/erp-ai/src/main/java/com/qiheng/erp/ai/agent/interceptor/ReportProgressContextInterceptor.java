package com.qiheng.erp.ai.agent.interceptor;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.qiheng.erp.ai.agent.AgentRunKeys;
import com.qiheng.erp.ai.report.ReportProgress;

import java.util.HashMap;
import java.util.function.Consumer;

/** 将本次报告的真实工具调用标识与事件通道交给报告图。 */
public final class ReportProgressContextInterceptor extends ToolInterceptor {
    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        if (!"createReport".equals(request.getToolName())) {
            return handler.call(request);
        }
        var execution = request.getExecutionContext().orElseThrow();
        var emitter = (Consumer<NodeOutput>) execution.config().metadata(AgentRunKeys.TOOL_EVENTS).orElse(null);
        var context = new HashMap<>(request.getContext());
        context.put(ReportProgress.METADATA_KEY, new ReportProgress(request.getToolCallId(), (name, data) -> {
            if (emitter != null) {
                emitter.accept(NodeOutput.of(name, "main_agent", new OverAllState(data), null));
            }
        }));
        return handler.call(ToolCallRequest.builder(request).context(context).executionContext(execution).build());
    }

    @Override
    public String getName() {
        return "report_progress_context";
    }
}
