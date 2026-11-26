package com.qiheng.erp.ai.draft;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 采购草稿工具：只声明参数结构，执行由人工确认节点接管。
 *
 * @author Li
 * @since 2026-11-22
 */
public final class PurchaseOrderDraftTool {

    /**
     * 声明采购草稿的参数结构；图会在工具执行前停在人工确认节点。
     *
     * @param request 草稿参数
     * @return 不会返回；误配置直接抛出明确错误
     */
    @Tool(name = "prepare_purchase_order_draft", description = "把采购单草稿交给用户确认，并在用户处置后返回结果。"
            + "只在需要生成采购草稿请用户确认时调用；供应商、仓库和产品必须传数字主键 ID，"
            + "不能传 S0001、WH000001、P001029 这类业务编码；用户只给编码时先用查询工具把编码换成主键 ID，再生成草稿，编码会被业务接口拒绝。"
            + "单价可以省略，确认卡片会按供货关系最新报价回填；没有有效报价时让用户补价或先维护供货关系，不要编造。"
            + "返回的是结论文本：第一句说明用户做了什么，需要精确数值时再读后面的参数块。"
            + "处置结果按 purchase-order 技能处理。")
    public String prepare(
            @ToolParam(description = "采购草稿参数")
            PurchaseOrderDraftRequest request) {
        throw new IllegalStateException("采购草稿必须由人工确认节点处理，工具方法不应直接执行");
    }
}