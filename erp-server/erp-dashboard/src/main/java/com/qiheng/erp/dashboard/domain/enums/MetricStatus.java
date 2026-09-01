package com.qiheng.erp.dashboard.domain.enums;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Locale;

/**
 * 指标风险色语义枚举。
 *
 * <p>与 OpenAPI {@code DashboardMetric.status} 和 {@code DashboardTodoEvidenceMetric.tone} 字段保持一致。
 * 序列化时输出小写字符串（good / watch / risk / neutral），前端零改动。</p>
 */
public enum MetricStatus {

    /** 良好 */
    GOOD,

    /** 关注 */
    WATCH,

    /** 风险 */
    RISK,

    /** 中性 */
    NEUTRAL;

    @JsonValue
    public String toLower() {
        return name().toLowerCase(Locale.ROOT);
    }
}
