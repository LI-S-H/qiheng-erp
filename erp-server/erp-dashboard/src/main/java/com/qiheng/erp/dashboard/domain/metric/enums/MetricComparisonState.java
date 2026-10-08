package com.qiheng.erp.dashboard.domain.metric.enums;

/** 指标对比可用性；权限仍由概览 access 表达，不把查询失败当作零基线。 */
public enum MetricComparisonState {
    /** 已得到可计算的对比率，包括本期和上期均为零。 */
    AVAILABLE,
    /** 正常缺少历史快照，或上期为零且本期非零。 */
    NO_BASELINE,
    /** 对比数据读取或回算失败，本期数值仍保留。 */
    UNAVAILABLE
}
