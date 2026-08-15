package com.qiheng.erp.dashboard.service;

import com.qiheng.erp.dashboard.domain.vo.DashboardNotificationPopoverVO;

/**
 * 工作台顶栏通知铃铛摘要服务接口。
 *
 * <p>对应 OpenAPI {@code GET /dashboard/notifications}，承载顶栏通知弹层数据。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
public interface IDashboardNotificationService {

    /**
     * 加载顶栏通知铃铛摘要
     * @return 前 8 条待办及总数统计
     */
    DashboardNotificationPopoverVO popover();
}