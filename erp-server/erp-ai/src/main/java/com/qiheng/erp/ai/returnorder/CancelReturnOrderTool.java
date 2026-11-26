package com.qiheng.erp.ai.returnorder;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 取消退货单工具：只声明参数结构，执行由人工确认节点接管。
 *
 * @author Li
 * @since 2026-11-26
 */
public final class CancelReturnOrderTool {

    /**
     * 声明取消退货单的参数结构；图会在工具执行前停在人工确认节点。
     *
     * @param request 退货单标识与原因
     * @return 不会返回；误配置直接抛出明确错误
     */
    @Tool(name = "cancel_return_order", description = "取消退货单。"
            + "用户明确要求取消，或你建议取消且用户已经同意时才调用。"
            + "已部分执行、已完成、已取消的不能取消；已审核但没有任何执行事实的可以取消，"
            + "会作废未确认的退货入库/出库工作单。"
            + "从用户的话里提取取消原因作为 reason，没有就留空。"
            + "返回的结论文本按 return-order 技能处理。")
    public String cancel(
            @ToolParam(description = "退货单标识：returnOrderId 必填且必须是数字 ID（不是 PR/SR 单号），orderNo 和 reason 可选")
            ReturnOrderActionRequest request) {
        throw new IllegalStateException("退货单取消必须由人工确认节点处理，工具方法不应直接执行");
    }
}