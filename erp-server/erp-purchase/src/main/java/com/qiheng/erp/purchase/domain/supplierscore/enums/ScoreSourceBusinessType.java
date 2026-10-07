package com.qiheng.erp.purchase.domain.supplierscore.enums;

/** 评分变更来源所属的业务对象，避免不同类型的 ID 和编号混用。 */
public enum ScoreSourceBusinessType {
    PURCHASE_ORDER,
    SUPPLIER_PRODUCT,
    PRODUCT,
    SUPPLIER
}
