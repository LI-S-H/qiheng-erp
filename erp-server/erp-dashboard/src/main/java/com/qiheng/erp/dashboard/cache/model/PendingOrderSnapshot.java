package com.qiheng.erp.dashboard.cache.model;

/**
 * 工作台待处理订单的日快照分项。
 *
 * <p>按待办来源保存，读取时再依据当前用户权限组合。退货审核与订单审核拆分保存，
 * 避免不同销售、采购权限账户把公司总数作为环比基线。</p>
 */
public record PendingOrderSnapshot(
        long pendingPurchaseOrderApproval,
        long pendingPurchaseReturnApproval,
        long pendingSalesOrderApproval,
        long pendingSalesReturnApproval,
        long pendingInboundConfirmation,
        long pendingOutboundConfirmation
) {
}