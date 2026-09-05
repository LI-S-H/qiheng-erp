package com.qiheng.erp.dashboard.domain.enums;

/**
 * 单据待办的等待时长分级。
 *
 * <p>小于 24 小时为 NORMAL，24 至 72 小时为 WARNING，72 小时及以上为 OVERDUE。</p>
 */
public enum DashboardTodoWaitLevel {

    NORMAL,
    WARNING,
    OVERDUE
}
