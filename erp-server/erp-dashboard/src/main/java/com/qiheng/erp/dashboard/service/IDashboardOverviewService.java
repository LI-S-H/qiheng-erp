package com.qiheng.erp.dashboard.service;

import com.qiheng.erp.dashboard.domain.vo.DashboardOverviewVO;

/**
 * 工作台经营概览服务接口。
 *
 * <p>对应 OpenAPI {@code GET /dashboard/overview}，
 * 返回当前登录用户可见范围内的经营概览数据，用于工作台首屏展示。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
public interface IDashboardOverviewService {

    /**
     * 加载工作台经营概览
     * @return 工作台概览 VO；无权限模块对应字段为空数组或 0 值
     */
    DashboardOverviewVO overview();
}