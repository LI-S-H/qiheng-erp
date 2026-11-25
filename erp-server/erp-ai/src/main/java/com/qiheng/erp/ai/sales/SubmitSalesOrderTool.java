package com.qiheng.erp.ai.sales;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 提交销售单审核工具：只声明参数结构，执行由人工确认节点接管。
 *
 * @author Li
 * @since 2026-11-23
 */
public final class SubmitSalesOrderTool {

    /**
     * 声明提交审核的参数结构；图会在工具执行前停在人工确认节点。
     *
     * @param request 销售单标识
     * @return 不会返回；误配置直接抛出明确错误
     */
    @Tool(name = "submit_sales_order", description = "把草稿状态的销售单提交审核。"
            + "只在用户同意提交时调用；单据不是草稿时不要调用，先把当前状态告诉用户。"
            + "提交不产生新参数，预计发货为空时由用户在确认卡片上补，不要编造日期。"
            + "返回的结论文字会说明是已提交、暂不提交还是提交未通过，按 sales-order 技能处理。")
    public String submit(
            @ToolParam(description = "销售单标识：salesOrderId 必填，orderNo 可选")
            SalesOrderActionRequest request) {
        throw new IllegalStateException("销售单提交必须由人工确认节点处理，工具方法不应直接执行");
    }
}