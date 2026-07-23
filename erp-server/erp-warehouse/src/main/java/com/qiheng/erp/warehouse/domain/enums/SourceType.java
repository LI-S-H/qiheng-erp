package com.qiheng.erp.warehouse.domain.enums;

/**
 * 原业务来源类型。
 * 对应 stock_bill.business_source_type，确认时从工作单复制的历史快照。
 */
public enum SourceType {

    /**
     * 采购订单。
     */
    PURCHASE_ORDER,

    /**
     * 销售订单。
     */
    SALES_ORDER,

    /**
     * 采购退货单。
     */
    PURCHASE_RETURN_ORDER,

    /**
     * 销售退货单。
     */
    SALES_RETURN_ORDER,

    /**
     * 库存调整单。
     */
    STOCK_ADJUST
}