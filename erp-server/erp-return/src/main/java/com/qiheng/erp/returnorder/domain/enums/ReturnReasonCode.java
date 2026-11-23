package com.qiheng.erp.returnorder.domain.enums;

/**
 * 退货原因编码。名称与 OpenAPI、前端选项保持一致，历史旧码已迁移为规范编码。
 */
public enum ReturnReasonCode {
    QUALITY_ISSUE,
    DAMAGED,
    WRONG_ITEM,
    QUANTITY_ERROR,
    SPEC_MISMATCH,
    NO_LONGER_NEEDED,
    OTHER;
}
