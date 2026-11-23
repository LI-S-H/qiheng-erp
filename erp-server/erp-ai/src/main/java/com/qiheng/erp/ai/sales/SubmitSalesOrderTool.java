package com.qiheng.erp.ai.sales;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.qiheng.erp.ai.agent.TrustedToolContextInterceptor;
import com.qiheng.erp.ai.context.AgentToolContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.function.Consumer;

/**
 * 提交销售单审核工具：等待用户确认后把结果交回模型。
 *
 * @author Li
 * @since 2026-11-23
 */
public final class SubmitSalesOrderTool {

    private final SalesOrderActionService actions;

    /**
     * 注入负责等待用户处置的动作服务。
     *
     * @param actions 动作服务
     */
    public SubmitSalesOrderTool(SalesOrderActionService actions) {
        this.actions = actions;
    }

    /**
     * 提交审核并等待用户确认。
     *
     * @param request 销售单标识
     * @param context 工具上下文
     * @return 用户处置结果
     * @throws Exception 等待被中断或超时
     */
    @Tool(name = "submit_sales_order", description = "把草稿状态的销售单提交审核。"
            + "只在用户同意提交时调用；单据不是草稿时不要调用，先把当前状态告诉用户。"
            + "提交不产生新参数，预计发货为空时由用户在确认卡片上补，不要编造日期。"
            + "返回的结论文字会说明是已提交、暂不提交还是提交未通过，按 sales-order 技能处理。")
    public String submit(
            @ToolParam(description = "销售单标识：salesOrderId 必填，orderNo 可选")
            SalesOrderActionRequest request,
            ToolContext context) throws Exception {
        var identity = ToolContextHelper.getMetadata(context, AgentToolContext.CONTEXT_KEY, AgentToolContext.class)
                .orElseThrow(() -> new IllegalStateException("缺少可信销售单工具上下文"));
        @SuppressWarnings("unchecked")
        var events = (Consumer<NodeOutput>) context.getContext().get(SalesOrderActionService.EVENT_KEY);
        Object runId = context.getContext().get(TrustedToolContextInterceptor.CURRENT_RUN_ID);
        return actions.request(SalesOrderActionType.SUBMIT, identity.userId(), identity.conversationId(),
                runId instanceof String value ? value : null, request, events);
    }
}