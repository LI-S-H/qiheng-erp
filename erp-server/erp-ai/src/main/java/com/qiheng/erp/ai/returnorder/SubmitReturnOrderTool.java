package com.qiheng.erp.ai.returnorder;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 提交退货单审核工具：只声明参数结构，执行由人工确认节点接管。
 *
 * @author Li
 * @since 2026-11-26
 */
public final class SubmitReturnOrderTool {

    /**
     * 声明提交审核的参数结构；图会在工具执行前停在人工确认节点。
     *
     * @param request 退货单标识
     * @return 不会返回；误配置直接抛出明确错误
     */
    @Tool(name = "submit_return_order", description = "把草稿状态的退货单提交审核。"
            + "只在用户同意提交时调用；单据不是草稿时不要调用，先把当前状态告诉用户。"
            + "提交不产生新参数；预计执行日期为空时由用户在确认卡片上补，不要编造日期。"
            + "提交后进入待审核，审核通过后才生成退货入库/出库单，不要说成已执行。"
            + "返回的结论文本按 return-order 技能处理。")
    public String submit(
            @ToolParam(description = "退货单标识：returnOrderId 必填且必须是数字 ID（不是 PR/SR 单号），orderNo 可选")
            ReturnOrderActionRequest request) {
        throw new IllegalStateException("退货单提交必须由人工确认节点处理，工具方法不应直接执行");
    }
}