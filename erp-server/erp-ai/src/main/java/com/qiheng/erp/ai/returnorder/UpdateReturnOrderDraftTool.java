package com.qiheng.erp.ai.returnorder;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 退货单修改工具：只声明参数结构，执行由人工确认节点接管。
 *
 * @author Li
 * @since 2026-11-26
 */
public final class UpdateReturnOrderDraftTool {

    /**
     * 声明退货单修改的参数结构；图会在工具执行前停在人工确认节点。
     *
     * @param request 修改参数，必须带 returnOrderId
     * @return 不会返回；误配置直接抛出明确错误
     */
    @Tool(name = "update_return_order_draft", description = "修改已经存在的退货单，交用户确认后保存。"
            + "必须传 returnOrderId（退货单数字 ID，不是 PR/SR 单号），并带齐 returnType、sourceOrderId、warehouseId、handlingType、reasonCode、"
            + "returnReason 和完整 items；预计执行日期、备注可以省略。"
            + "只在退货单已创建、状态为草稿或待审核时调用。"
            + "来源单和仓库不能改；items 要传完整明细，数量使用 sourceOrderItemId + requestedQty。"
            + "修改数量时要按排除当前退货单自身占用后的可退上限核对。"
            + "预计执行日期为空时由确认卡片补，草稿和待审核修改时填写日期均不得早于今天。"
            + "返回的结论文本按 return-order 技能处理。")
    public String update(
            @ToolParam(description = "退货单修改参数，必须包含 returnOrderId")
            ReturnOrderDraftRequest request) {
        throw new IllegalStateException("退货单修改必须由人工确认节点处理，工具方法不应直接执行");
    }
}