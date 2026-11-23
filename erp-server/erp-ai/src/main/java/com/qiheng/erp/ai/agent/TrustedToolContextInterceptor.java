package com.qiheng.erp.ai.agent;

import com.qiheng.erp.ai.clarification.ClarificationService;
import com.qiheng.erp.ai.action.PurchaseOrderActionService;
import com.qiheng.erp.ai.draft.PurchaseDraftService;
import com.qiheng.erp.ai.sales.SalesOrderActionService;
import com.qiheng.erp.ai.sales.SalesOrderDraftService;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.analysis.AnalysisTaskContext;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;

import java.util.HashMap;

/**
 * 将服务端身份注入工具上下文，避免依赖工具执行线程的登录信息。
 *
 * @author Li
 * @since 2026-10-27
 */
public final class TrustedToolContextInterceptor extends ToolInterceptor {
    public static final String TRUSTED_CONTEXT = "trustedSqlContext";
    public static final String CURRENT_RUN_ID = "currentRunId";

    /**
     * 把服务端身份、任务对象与事件通道写入工具上下文。
     *
     * @param request 本次工具调用请求
     * @param handler 后续拦截器与工具的执行链
     * @return 工具执行结果
     */
    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        AgentToolContext trusted = (AgentToolContext) request.getExecutionContext().orElseThrow()
                .config().metadata(TRUSTED_CONTEXT).orElseThrow();
        var context = new HashMap<>(request.getContext());
        context.put(AgentToolContext.CONTEXT_KEY, trusted);
        // 当前轮次只能从服务端运行配置中获取。
        context.remove(CURRENT_RUN_ID);
        request.getExecutionContext().orElseThrow().config().metadata("runId")
                .ifPresent(value -> context.put(CURRENT_RUN_ID, value));
        // 任务对象只从服务端运行配置注入，不接受外部上下文冒充。
        context.remove(AnalysisTaskContext.CONTEXT_KEY);
        request.getExecutionContext().orElseThrow().config().metadata(AnalysisTaskContext.CONTEXT_KEY)
                .ifPresent(value -> context.put(AnalysisTaskContext.CONTEXT_KEY, value));
        // 当前分析任务 ID 仅接受服务端运行配置，避免模型传入的上下文冒充。
        context.remove(AnalysisResultTool.CONTEXT_TASK_ID);
        request.getExecutionContext().orElseThrow().config().metadata(AnalysisResultTool.CONTEXT_TASK_ID)
                .ifPresent(value -> context.put(AnalysisResultTool.CONTEXT_TASK_ID, value));
        request.getExecutionContext().orElseThrow().config().metadata(ClarificationService.EVENT_KEY)
                .ifPresent(value -> context.put(ClarificationService.EVENT_KEY, value));
        // 草稿确认与追问一样需要把事件通道交给工具，否则无法向页面发出待确认事件。
        request.getExecutionContext().orElseThrow().config().metadata(PurchaseDraftService.EVENT_KEY)
                .ifPresent(value -> context.put(PurchaseDraftService.EVENT_KEY, value));
        // 提交和取消的确认同样需要事件通道。
        request.getExecutionContext().orElseThrow().config().metadata(PurchaseOrderActionService.EVENT_KEY)
                .ifPresent(value -> context.put(PurchaseOrderActionService.EVENT_KEY, value));
        // 销售草稿与提交、取消同样需要事件通道。
        request.getExecutionContext().orElseThrow().config().metadata(SalesOrderDraftService.EVENT_KEY)
                .ifPresent(value -> context.put(SalesOrderDraftService.EVENT_KEY, value));
        request.getExecutionContext().orElseThrow().config().metadata(SalesOrderActionService.EVENT_KEY)
                .ifPresent(value -> context.put(SalesOrderActionService.EVENT_KEY, value));
        // 框架复制构建器不会保留执行上下文，后续拦截器仍需读取恢复配置。
        return handler.call(ToolCallRequest.builder(request)
                .context(context).executionContext(request.getExecutionContext().orElseThrow()).build());
    }

    /** 提供拦截器在主智能体中的名称。 */
    @Override
    public String getName() {
        return "trusted_tool_context";
    }
}
