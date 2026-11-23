package com.qiheng.erp.ai.draft;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.qiheng.erp.ai.agent.TrustedToolContextInterceptor;
import com.qiheng.erp.ai.context.AgentToolContext;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.function.Consumer;

/**
 * 采购单修改工具：把修改后的参数交给用户确认，并在用户处置后把事实交回模型。
 *
 * @author Li
 * @since 2026-11-23
 */
public final class UpdatePurchaseOrderDraftTool {

    private final PurchaseDraftService drafts;

    /**
     * 注入负责等待用户处置的草稿服务。
     *
     * @param drafts 草稿服务
     */
    public UpdatePurchaseOrderDraftTool(PurchaseDraftService drafts) {
        this.drafts = drafts;
    }

    /**
     * 提交修改给用户确认并等待结果。
     *
     * @param request 修改参数，必须带 purchaseOrderId
     * @param context 工具上下文
     * @return 用户处置结果，供模型继续分析
     * @throws Exception 等待被中断或超时
     */
    @Tool(name = "update_purchase_order_draft", description = "修改已经存在的采购单参数，交用户确认后保存。"
            + "只在采购单已创建、用户明确要求修改数量、单价、仓库、到货日期或明细时调用；单据还不存在时不要调用，先走创建流程。"
            + "必须传 purchaseOrderId；明细要传完整的 items，没提到的字段可以省略，确认卡片会以单据现值为底。"
            + "返回的结论文字会说明是已保存、修改后保存、被驳回还是修改未保存，按 purchase-order 技能处理。")
    public String update(
            @ToolParam(description = "采购单修改参数，必须包含 purchaseOrderId")
            PurchaseOrderDraftRequest request,
            ToolContext context) throws Exception {
        var identity = ToolContextHelper.getMetadata(context, AgentToolContext.CONTEXT_KEY, AgentToolContext.class)
                .orElseThrow(() -> new IllegalStateException("缺少可信采购草稿工具上下文"));
        @SuppressWarnings("unchecked")
        var events = (Consumer<NodeOutput>) context.getContext().get(PurchaseDraftService.EVENT_KEY);
        Object runId = context.getContext().get(TrustedToolContextInterceptor.CURRENT_RUN_ID);
        return drafts.request(PurchaseOrderDraftAction.UPDATE, identity.userId(), identity.conversationId(),
                runId instanceof String value ? value : null, request, events);
    }
}