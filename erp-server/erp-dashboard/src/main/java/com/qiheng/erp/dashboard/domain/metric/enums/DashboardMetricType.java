package com.qiheng.erp.dashboard.domain.metric.enums;

/**
 * 工作台首屏经营指标类型。
 *
 * <p>与 {@code DashboardMetric.label} 和 OpenAPI {@code DashboardMetric.status} 对应。</p>
 */
public enum DashboardMetricType {

    /** 今日销售额（单位：元） */
    TODAY_SALES,

    /** 本月毛利额（单位：元） */
    MONTH_GROSS,

    /** 待处理订单数（采购 + 销售 + 入库 + 出库，单位：单） */
    PENDING_ORDER,

    /** 库存风险 SKU 数（available_qty 低于 safety_stock_qty，单位：个） */
    STOCK_RISK
}