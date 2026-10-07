package com.qiheng.erp.purchase.domain.supplierproduct.enums;

/**
 * 评分状态;Supplier 与 SupplierProduct 共用同一组枚举值,通过 name() 与数据库 CHECK 约束对齐。
 */
public enum SupplierScoreStatus {
    /** 评分样本不足,不可参与推荐。 */
    NOT_READY,
    /** 评分样本充足,可参与推荐。 */
    READY
}