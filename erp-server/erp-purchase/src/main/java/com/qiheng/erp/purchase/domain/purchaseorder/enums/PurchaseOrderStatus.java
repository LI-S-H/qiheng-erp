package com.qiheng.erp.purchase.domain.purchaseorder.enums;

/**
 * 采购订单状态。
 * 对应 purchase_order.status。
 */
public enum PurchaseOrderStatus {

    /**
     * 草稿
     */
    DRAFT,

    /**
     * 已提交
     */
    SUBMITTED,

    /**
     * 已审核
     */
    APPROVED,

    /**
     * 部分入库
     */
    PARTIAL_INBOUND,

    /**
     * 入库完成
     */
    INBOUND_DONE,

    /**
     * 已取消
     */
    CANCELLED
}
