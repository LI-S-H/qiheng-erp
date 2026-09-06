package com.qiheng.erp.common.event.dashboard;

import java.time.LocalDate;

/**
 * 已提交业务事务变更了某日经营金额时发布。
 *
 * <p>监听器仅删除对应日期和维度的缓存字段；下一次读取时按需回填，避免整段趋势重建。</p>
 */
public record DashboardTrendInvalidatedEvent(
        DashboardTrendMetric metric,
        LocalDate businessDate
) { }