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
 * 采购草稿工具：把草稿交给用户确认，并在用户处置后把事实交回模型。
 *
 * @author Li
 * @since 2026-11-22
 */
public final class PurchaseOrderDraftTool {

    private final PurchaseDraftService drafts;

    /**
     * 注入负责等待用户处置的草稿服务。
     *
     * @param drafts 草稿服务
     */
    public PurchaseOrderDraftTool(PurchaseDraftService drafts) {
        this.drafts = drafts;
    }

    /**
     * 提交草稿给用户确认并等待处置结果。
     *
     * @param request 草稿参数
     * @param context 工具上下文
     * @return 用户处置结果，供模型继续分析
     * @throws Exception 等待被中断或超时
     */
    @Tool(name = "prepare_purchase_order_draft", description = "把采购单草稿交给用户确认，并在用户处置后返回结果。"
            + "只在需要生成采购草稿请用户确认时调用；供应商、仓库和产品必须使用系统中真实存在的编号，先查询确认，不要编造。"
            + "返回的是结论文字：第一句说明用户做了什么，后面按需附带参数 JSON，先读结论、需要精确数值时再读参数块。"
            + "处置结果按 purchase-order 技能处理。")
    public String prepare(
            @ToolParam(description = "采购草稿参数")
            PurchaseOrderDraftRequest request,
            ToolContext context) throws Exception {
        var identity = ToolContextHelper.getMetadata(context, AgentToolContext.CONTEXT_KEY, AgentToolContext.class)
                .orElseThrow(() -> new IllegalStateException("缺少可信采购草稿工具上下文"));
        @SuppressWarnings("unchecked")
        var events = (Consumer<NodeOutput>) context.getContext().get(PurchaseDraftService.EVENT_KEY);
        // 草稿记录按本轮运行标识归组，刷新后仍能在同一轮对话里看到只读卡片。
        Object runId = context.getContext().get(TrustedToolContextInterceptor.CURRENT_RUN_ID);
        return drafts.request(PurchaseOrderDraftAction.CREATE, identity.userId(), identity.conversationId(),
                runId instanceof String value ? value : null, request, events);
    }
}