package com.qiheng.erp.warehouse.domain.enums;

/**
 * 入库单状态。
 * 对应 inbound_bill.status。
 */
public enum InboundBillStatus {

    /**
     * 草稿。
     */
    DRAFT,

    /**
     * 待确认。
     */
    PENDING_CONFIRM,

    /**
     * 已确认。
     */
    CONFIRMED,

    /**
     * 已取消。
     */
    CANCELLED
}