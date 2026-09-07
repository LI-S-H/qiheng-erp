package com.qiheng.erp.dashboard.domain.overview.enums;

/**
 * 工作台风险色语义。
 *
 * <p>与 OpenAPI {@code DashboardMetric.status}保持一致。</p>
 */
public enum DashboardSeverity {

    /** 良好 */
    GOOD,

    /** 关注 */
    WATCH,

    /** 风险 */
    RISK,

    /** 中性 */
    NEUTRAL,

    /** 高优先级待办 */
    HIGH,

    /** 中等优先级待办 */
    MEDIUM,

    /** 低优先级待办 */
    LOW
}