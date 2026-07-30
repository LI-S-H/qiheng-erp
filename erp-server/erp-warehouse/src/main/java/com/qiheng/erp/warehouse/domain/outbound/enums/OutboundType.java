package com.qiheng.erp.warehouse.domain.outbound.enums;

import com.qiheng.erp.warehouse.domain.common.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.common.enums.SourceType;
import com.qiheng.erp.warehouse.domain.support.StockBillTypePolicy;

/**
 * 出库单类型。
 * 对应 outbound_bill.outbound_type。
 */
public enum OutboundType implements StockBillTypePolicy {

    /**
     * 销售出库。
     */
    SALES_OUT(SourceType.SALES_ORDER, EntryMode.MANUAL_SUPPLEMENT, false),

    /**
     * 采购退货出库。
     */
    PURCHASE_RETURN(SourceType.PURCHASE_RETURN_ORDER, EntryMode.MANUAL_SUPPLEMENT, true),

    /**
     * 库存调整出库。
     */
    ADJUST_OUT(SourceType.STOCK_ADJUST, EntryMode.MANUAL_ADJUSTMENT, false);

    private final SourceType sourceType;
    private final EntryMode entryMode;
    private final boolean requiresQualityCheck;

    OutboundType(SourceType sourceType, EntryMode entryMode, boolean requiresQualityCheck) {
        this.sourceType = sourceType;
        this.entryMode = entryMode;
        this.requiresQualityCheck = requiresQualityCheck;
    }

    @Override
    public SourceType sourceType() {
        return sourceType;
    }

    @Override
    public EntryMode entryMode() {
        return entryMode;
    }

    @Override
    public String billNoPrefix() {
        return "OB";
    }

    @Override
    public String directionName() {
        return "出库";
    }

    @Override
    public boolean requiresQualityCheck() {
        return requiresQualityCheck;
    }
}
