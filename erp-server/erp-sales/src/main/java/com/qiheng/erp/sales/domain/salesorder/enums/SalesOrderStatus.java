package com.qiheng.erp.sales.domain.salesorder.enums;

/**
 * 统一销售订单状态。
 *
 * <p>枚举名称与 {@code sales_order.status}、OpenAPI 和前端状态值保持一致。</p>
 */
public enum SalesOrderStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    PARTIAL_OUTBOUND,
    OUTBOUND_DONE,
    CANCELLED
}