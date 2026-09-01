package com.qiheng.erp.dashboard.cache.model;

/**
 * 工作台待处理订单的日快照分项。
 *
 * <p>按待办来源保存，读取时再依据当前用户权限组合，避免不同权限账号使用
 * 全公司总数作为环比基线。</p>
 */
public record PendingOrderSnapshot(
        long pendingPurchase,
        long pendingSales,
        long pendingInbound,
        long pendingOutbound
) {
}