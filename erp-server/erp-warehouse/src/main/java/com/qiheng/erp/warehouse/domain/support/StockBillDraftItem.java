package com.qiheng.erp.warehouse.domain.support;

import java.math.BigDecimal;

/**
 * 手工出入库单明细创建时需要的公共输入。
 */
public interface StockBillDraftItem {

    String getProductId();

    BigDecimal getCurrentQty();

    BigDecimal getQualifiedQty();

    BigDecimal getDefectiveQty();

}
