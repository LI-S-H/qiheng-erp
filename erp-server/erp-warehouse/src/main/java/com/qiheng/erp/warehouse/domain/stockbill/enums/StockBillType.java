package com.qiheng.erp.warehouse.domain.stockbill.enums;

/**
 * 出入库类型。
 * 对应 stock_bill.bill_type，已确认库存流水使用。
 */
public enum StockBillType {

    /**
     * 采购入库。
     */
    PURCHASE_IN,

    /**
     * 销售出库。
     */
    SALES_OUT,

    /**
     * 采购退货出库。
     */
    PURCHASE_RETURN,

    /**
     * 销售退货入库。
     */
    SALES_RETURN,

    /**
     * 调整入库。
     */
    ADJUST_IN,

    /**
     * 调整出库。
     */
    ADJUST_OUT
}