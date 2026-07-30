package com.qiheng.erp.warehouse.domain.common.enums;

/**
 * 录入方式。
 * 对应 stock_bill.entry_mode，确认时从工作单复制的历史快照。
 */
public enum EntryMode {

    /**
     * 系统自动录入。
     */
    SOURCE_GENERATED,

    /**
     * 人工补录。
     */
    MANUAL_SUPPLEMENT,

    /**
     * 人工调整。
     */
    MANUAL_ADJUSTMENT
}