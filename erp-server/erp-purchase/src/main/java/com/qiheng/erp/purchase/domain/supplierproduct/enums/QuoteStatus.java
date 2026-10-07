package com.qiheng.erp.purchase.domain.supplierproduct.enums;

/**
 * 供货关系报价状态;NONE 表示无报价,VALID 表示有报价且未过期,EXPIRED 表示有报价但已过期。
 */
public enum QuoteStatus {
    NONE,
    VALID,
    EXPIRED
}