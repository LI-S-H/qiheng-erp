package com.qiheng.erp.dashboard.domain.enums;

/**
 * 工作台区块的可见状态。
 *
 * <p>该状态由后端基于当前用户权限和实际返回数据生成，前端据此区分无权限、暂无数据和
 * 当前账号可查看口径的数据，不得以 0 或空数组猜测权限。</p>
 */
public enum DashboardAccessState {
    ALLOWED,
    EMPTY,
    DENIED
}