package com.qiheng.erp.ai.agent.interceptor;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.qiheng.erp.ai.agent.AgentRunKeys;
import com.qiheng.erp.ai.analysis.AnalysisProgress;
import com.qiheng.erp.ai.analysis.AnalysisTool;

import java.util.HashMap;
import java.util.function.Consumer;

/**
 * 为分析工具注入过程通道，使分析智能体的思路与结论能回到主智能体的事件流。
 *
 * @author Li
 * @since 2026-11-10
 */
public final class AnalysisProgressContextInterceptor extends ToolInterceptor {

    /**
     * 为分析工具注入过程通道，使分析智能体的思路与结论能回到主智能体的事件流。
     *
     * @param request 本次工具调用请求
     * @param handler 后续拦截器与工具的执行链
     * @return 工具执行结果
     */
    @Override
    @SuppressWarnings("unchecked")
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        if (!AnalysisTool.TOOL_NAME.equals(request.getToolName())) {
            return handler.call(request);
        }
        var callback = request.getExecutionContext().flatMap(context -> context.config().metadata(AgentRunKeys.TOOL_EVENTS));
        if (callback.isEmpty()) {
            return handler.call(request);
        }
        var emit = (Consumer<NodeOutput>) callback.get();
        // 分析过程事件统一以主智能体节点名义发出，保证与工具状态使用同一条事件流。
        var progress = new AnalysisProgress(request.getToolCallId(), (name, data) ->
                emit.accept(NodeOutput.of(name, "main_agent", new OverAllState(data), null)));
        var context = new HashMap<>(request.getContext());
        // 过程通道只接受服务端构造的值，不接受外部上下文冒充。
        context.remove(AnalysisProgress.CONTEXT_KEY);
        context.put(AnalysisProgress.CONTEXT_KEY, progress);
        return handler.call(ToolCallRequest.builder(request)
                .context(context)
                .executionContext(request.getExecutionContext().orElseThrow())
                .build());
    }

    /** 提供拦截器在主智能体中的名称。 */
    @Override
    public String getName() {
        return "analysis_progress_context";
    }
}
