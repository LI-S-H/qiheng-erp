package com.qiheng.erp.warehouse.domain.enums;

/**
 * 出库单类型。
 * 对应 outbound_bill.outbound_type。
 */
public enum OutboundType {

    /**
     * 销售出库。
     */
    SALES_OUT,

    /**
     * 采购退货出库。
     */
    PURCHASE_RETURN,

    /**
     * 库存调整出库。
     */
    ADJUST_OUT
}
