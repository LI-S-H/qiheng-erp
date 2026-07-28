package com.qiheng.erp.warehouse.domain.support;

import com.qiheng.erp.warehouse.domain.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.enums.SourceType;

/**
 * 入库单、出库单类型的公共业务规则。
 *
 * <p>类型本身决定来源、录入方式、单号前缀和质检数量校验规则，避免这些规则散落在两个 Service 中维护。</p>
 */
public interface StockBillTypePolicy {

    SourceType sourceType();

    EntryMode entryMode();

    String billNoPrefix();

    String directionName();

    boolean requiresQualityCheck();
}
