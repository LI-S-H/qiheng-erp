package com.qiheng.erp.ai.sales;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 销售单修改工具：只声明参数结构，执行由人工确认节点接管。
 *
 * @author Li
 * @since 2026-11-23
 */
public final class UpdateSalesOrderDraftTool {

    /**
     * 声明销售单修改的参数结构；图会在工具执行前停在人工确认节点。
     *
     * @param request 修改参数，必须带 salesOrderId
     * @return 不会返回；误配置直接抛出明确错误
     */
    @Tool(name = "update_sales_order_draft", description = "修改已经存在的销售单参数，交用户确认后保存。"
            + "只在销售单已创建、用户明确要求修改数量、单价、仓库、发货日期或明细时调用；单据还不存在时不要调用，先走创建流程。"
            + "必须传 salesOrderId；明细要传完整的 items，没提到的字段可以省略，确认卡片会以单据现值为底。"
            + "客户、仓库、产品必须传数字主键 ID，不能传 C0001、WH000001、P001029 这类业务编码。"
            + "返回的结论文字会说明是已保存、修改后保存、被驳回还是修改未保存，按 sales-order 技能处理。")
    public String update(
            @ToolParam(description = "销售单修改参数，必须包含 salesOrderId")
            SalesOrderDraftRequest request) {
        throw new IllegalStateException("销售单修改必须由人工确认节点处理，工具方法不应直接执行");
    }
}