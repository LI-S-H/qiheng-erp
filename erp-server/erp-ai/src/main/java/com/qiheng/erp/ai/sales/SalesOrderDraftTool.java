package com.qiheng.erp.ai.sales;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 销售草稿工具：只声明参数结构，执行由人工确认节点接管。
 *
 * @author Li
 * @since 2026-11-22
 */
public final class SalesOrderDraftTool {

    /**
     * 声明销售草稿的参数结构；图会在工具执行前停在人工确认节点。
     *
     * @param request 草稿参数
     * @return 不会返回；误配置直接抛出明确错误
     */
    @Tool(name = "prepare_sales_order_draft", description = "把销售单草稿交给用户确认，并在用户处置后返回结果。"
            + "只在需要生成销售草稿请用户确认时调用；客户、仓库和产品必须传数字主键 ID，"
            + "不能传 C0001、WH000001、P001029 这类业务编码；用户只给编码时先用查询工具把编码换成主键 ID，再生成草稿，编码会被业务接口拒绝。"
            + "返回的是结论文字：第一句说明用户做了什么，后面按需附带参数 JSON，先读结论、需要精确数值时再读参数块。"
            + "处置结果按 sales-order 技能处理。")
    public String prepare(
            @ToolParam(description = "销售草稿参数")
            SalesOrderDraftRequest request) {
        throw new IllegalStateException("销售草稿必须由人工确认节点处理，工具方法不应直接执行");
    }
}