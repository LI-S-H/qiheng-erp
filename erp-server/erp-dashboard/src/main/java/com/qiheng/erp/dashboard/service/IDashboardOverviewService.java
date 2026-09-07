package com.qiheng.erp.dashboard.service;

import com.qiheng.erp.dashboard.domain.overview.vo.DashboardOverviewVO;

/**
 * 工作台经营概览服务接口。
 *
 * <p>对应 OpenAPI {@code GET /dashboard/overview}，
 * 返回当前登录用户可见范围内的经营概览数据，用于工作台首屏展示。</p>
 *
 * <p>内部按当前用户的业务 query / manage 模块裁剪；权限校验由调用方
 * 通过 Sa-Token 完成，本服务不再重复校验入口权限。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
public interface IDashboardOverviewService {

    /**
     * 加载工作台经营概览
     *
     * @return 工作台概览 VO；无权限模块保留固定字段结构，并由 access 明确标识为 DENIED；指标数值与对比字段返回 null，
     *         趋势 / 订单流转字段附带 {@code trendPermissions} /
     *         {@code orderStagePermissions} 用于前端三态渲染
     */
    DashboardOverviewVO overview();
}