package com.qiheng.erp.warehouse.domain.inbound.enums;

import com.qiheng.erp.warehouse.domain.common.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.common.enums.SourceType;
import com.qiheng.erp.warehouse.domain.support.StockBillTypePolicy;

public enum InboundType implements StockBillTypePolicy {
    /**
     * 采购入库
     */
    PURCHASE_IN(SourceType.PURCHASE_ORDER, EntryMode.MANUAL_SUPPLEMENT, true),

    /**
     * 销售退货入库
     */
    SALES_RETURN(SourceType.SALES_RETURN_ORDER, EntryMode.MANUAL_SUPPLEMENT, true),

    /**
     * 库存调整入库
     */
    ADJUST_IN(SourceType.STOCK_ADJUST, EntryMode.MANUAL_ADJUSTMENT, false);

    private final SourceType sourceType;
    private final EntryMode entryMode;
    private final boolean requiresQualityCheck;

    InboundType(SourceType sourceType, EntryMode entryMode, boolean requiresQualityCheck) {
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
        return "IB";
    }

    @Override
    public String directionName() {
        return "入库";
    }

    @Override
    public boolean requiresQualityCheck() {
        return requiresQualityCheck;
    }
}
