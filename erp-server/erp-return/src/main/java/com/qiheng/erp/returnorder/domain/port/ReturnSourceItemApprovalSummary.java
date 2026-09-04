package com.qiheng.erp.returnorder.domain.port;

/**
 * 单个来源订单明细下的退货审批汇总事实。
 *
 * @param sourceOrderItemId 来源订单明细 ID
 * @param approvedReturnQty 累计已审批退货数量，按数量放大规则存储
 * @param approvedReturnAmount 累计已审批退货金额，单位为分
 */
public record ReturnSourceItemApprovalSummary(
        Long sourceOrderItemId,
        Long approvedReturnQty,
        Long approvedReturnAmount
) {
}
