package com.qiheng.erp.ai.sales;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 取消销售单工具：只声明参数结构，执行由人工确认节点接管。
 *
 * @author Li
 * @since 2026-11-23
 */
public final class CancelSalesOrderTool {

    /**
     * 声明取消销售单的参数结构；图会在工具执行前停在人工确认节点。
     *
     * @param request 销售单标识与原因
     * @return 不会返回；误配置直接抛出明确错误
     */
    @Tool(name = "cancel_sales_order", description = "取消销售单。"
            + "用户明确要求取消、作废、不再销售，或你建议取消且用户已经同意时才调用；建议取消必须先征求用户同意，不要直接调用。"
            + "已出库、部分出库或已取消的单据不要调用。"
            + "从用户的话里提取取消原因作为 reason，没有就留空。"
            + "返回的结论文字会说明是已取消、暂不取消还是取消失败，按 sales-order 技能处理。")
    public String cancel(
            @ToolParam(description = "销售单标识：salesOrderId 必填，orderNo 和 reason 可选")
            SalesOrderActionRequest request) {
        throw new IllegalStateException("销售单取消必须由人工确认节点处理，工具方法不应直接执行");
    }
}