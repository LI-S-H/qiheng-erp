package com.qiheng.erp.returnorder.domain.port;

import java.util.Map;

/**
 * 单个来源订单下的退货审批汇总事实。
 *
 * @param sourceOrderId 来源订单 ID
 * @param returnOrderCount 关联退货单数，包含已取消退货单，供详情页决定是否展示退货区块
 * @param effectiveReturnOrderCount 处于已审批、部分执行或完成状态的退货单数
 * @param approvedReturnAmount 累计已审批退货金额，单位为分
 * @param itemSummaries 按来源订单明细 ID 汇总的审批事实
 */
public record ReturnSourceOrderApprovalSummary(
        Long sourceOrderId,
        int returnOrderCount,
        int effectiveReturnOrderCount,
        Long approvedReturnAmount,
        Map<Long, ReturnSourceItemApprovalSummary> itemSummaries
) {
}
