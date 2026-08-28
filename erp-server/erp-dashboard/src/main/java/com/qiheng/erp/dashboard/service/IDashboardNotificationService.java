package com.qiheng.erp.dashboard.service;

import com.qiheng.erp.dashboard.domain.vo.DashboardNotificationPopoverVO;

/**
 * 工作台顶栏通知铃铛摘要服务接口。
 *
 * <p>对应 OpenAPI {@code GET /dashboard/notifications}，
 * 仅承载弹层数据（前 8 条待办 + 总数 + 是否有更多），不返回经营趋势、订单流转等
 * 重型聚合，避免顶栏频繁点击触发不必要的 SQL。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
public interface IDashboardNotificationService {

    /**
     * 加载顶栏通知铃铛摘要
     *
     * @return 前 8 条待办及总数统计；待办按 {@code sortWeight} 升序、
     *         {@code priority} 升序、{@code completionMode} 处理方式、
     *         {@code occurredAt} 倒序兜底
     */
    DashboardNotificationPopoverVO popover();
}
