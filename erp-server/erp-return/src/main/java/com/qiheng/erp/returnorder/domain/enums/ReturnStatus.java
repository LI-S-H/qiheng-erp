package com.qiheng.erp.returnorder.domain.enums;

/**
 * 统一退货单状态。
 *
 * <p>枚举名称与 {@code return_order.status}、OpenAPI 和前端状态值保持一致。</p>
 */
public enum ReturnStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    PARTIAL_EXECUTED,
    COMPLETED,
    CANCELLED
}
