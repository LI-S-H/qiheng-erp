package com.qiheng.erp.sales.domain.salesorder.enums;

/**
 * 统一销售订单状态。
 *
 * <p>枚举名称与 {@code sales_order.status}、OpenAPI 和前端状态值保持一致。</p>
 */
public enum SalesOrderStatus {
    // 草稿
    DRAFT,
    // 已提交
    SUBMITTED,
    // 已审批
    APPROVED,
    // 部分出库
    PARTIAL_OUTBOUND,
    // 全部出库
    OUTBOUND_DONE,
    // 已取消
    CANCELLED
}