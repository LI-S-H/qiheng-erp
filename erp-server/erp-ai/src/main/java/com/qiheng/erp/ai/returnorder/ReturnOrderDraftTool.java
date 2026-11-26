package com.qiheng.erp.ai.returnorder;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 退货草稿工具：只声明参数结构，执行由人工确认节点接管。
 *
 * @author Li
 * @since 2026-11-26
 */
public final class ReturnOrderDraftTool {

    /**
     * 声明退货草稿的参数结构；图会在工具执行前停在人工确认节点。
     *
     * @param request 草稿参数
     * @return 不会返回；误配置直接抛出明确错误
     */
    @Tool(name = "prepare_return_order_draft", description = "把退货单草稿交给用户确认，并在用户处置后返回结果。"
            + "只在已经调用 query_returnable_items 核对来源单和可退数量后调用；工具不可用时按技能口径用 query_sql_data 核对。"
            + "returnType 只能是 SALES_RETURN 或 PURCHASE_RETURN；sourceOrderId、warehouseId、来源明细 ID 必须是数字主键，"
            + "不能传业务编码。仓库必须等于来源单仓库；items 使用 sourceOrderItemId，不要传 productId。"
            + "returnReason 必填；handlingType 未说明时按 REFUND 处理。"
            + "返回的是结论文本：第一句说明用户做了什么，需要精确数值时再读后面的参数块。"
            + "处置结果按 return-order 技能处理。")
    public String prepare(
            @ToolParam(description = "退货草稿参数")
            ReturnOrderDraftRequest request) {
        throw new IllegalStateException("退货草稿必须由人工确认节点处理，工具方法不应直接执行");
    }
}