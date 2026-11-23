package com.qiheng.erp.ai.action;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.qiheng.erp.ai.agent.TrustedToolContextInterceptor;
import com.qiheng.erp.ai.context.AgentToolContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.function.Consumer;

/**
 * 取消采购单工具：等待用户确认后把结果交回模型。
 *
 * @author Li
 * @since 2026-11-23
 */
public final class CancelPurchaseOrderTool {

    private final PurchaseOrderActionService actions;

    /**
     * 注入负责等待用户处置的动作服务。
     *
     * @param actions 动作服务
     */
    public CancelPurchaseOrderTool(PurchaseOrderActionService actions) {
        this.actions = actions;
    }

    /**
     * 取消采购单并等待用户确认。
     *
     * @param request 采购单标识与原因
     * @param context 工具上下文
     * @return 用户处置结果
     * @throws Exception 等待被中断或超时
     */
    @Tool(name = "cancel_purchase_order", description = "取消采购单。"
            + "用户明确要求取消、作废、不再采购，或你建议取消且用户已经同意时才调用；建议取消必须先征求用户同意，不要直接调用。"
            + "已入库或已取消的单据不要调用。"
            + "从用户的话里提取取消原因作为 reason，没有就留空。"
            + "返回的结论文字以「用户同意取消 / 用户暂不取消 / 取消失败」开头，按 purchase-order 技能处理。")
    public String cancel(
            @ToolParam(description = "采购单标识：purchaseOrderId 必填，orderNo 和 reason 可选")
            PurchaseOrderActionRequest request,
            ToolContext context) throws Exception {
        var identity = ToolContextHelper.getMetadata(context, AgentToolContext.CONTEXT_KEY, AgentToolContext.class)
                .orElseThrow(() -> new IllegalStateException("缺少可信采购单工具上下文"));
        @SuppressWarnings("unchecked")
        var events = (Consumer<NodeOutput>) context.getContext().get(PurchaseOrderActionService.EVENT_KEY);
        Object runId = context.getContext().get(TrustedToolContextInterceptor.CURRENT_RUN_ID);
        return actions.request(PurchaseOrderActionType.CANCEL, identity.userId(), identity.conversationId(),
                runId instanceof String value ? value : null, request, events);
    }
}