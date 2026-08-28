package com.qiheng.erp.dashboard.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.dashboard.domain.vo.DashboardNotificationPopoverVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardOverviewVO;
import com.qiheng.erp.dashboard.service.IDashboardNotificationService;
import com.qiheng.erp.dashboard.service.IDashboardOverviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 工作台控制器
 * </p>
 *
 * <p>对应 OpenAPI {@code Dashboard} 模块，提供工作台首屏经营概览和顶栏通知铃铛摘要。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@RestController
@RequestMapping("/dashboard")
@Tag(name = "工作台与顶栏通知")
@Slf4j
@RequiredArgsConstructor
public class DashboardController {
    // 工作台经营概览服务
    private final IDashboardOverviewService overviewService;
    // 顶栏通知服务
    private final IDashboardNotificationService notificationService;

    /**
     * 获取工作台经营概览
     *
     * <p>需要 {@code dashboard:overview:query} 权限；按当前用户的业务权限
     * 裁剪指标、趋势、订单流转等子项，详见 OpenAPI 描述。</p>
     *
     * @return 当前用户可见范围内的工作台经营概览数据
     */
    @GetMapping("/overview")
    @Operation(summary = "获取工作台经营概览")
    public Result<DashboardOverviewVO> overview() {
        StpUtil.checkPermission("dashboard:overview:query");
        log.info("加载工作台经营概览");
        return Result.ok(overviewService.overview());
    }

    /**
     * 获取顶栏通知铃铛摘要
     *
     * <p>需要 {@code dashboard:notifications:query} 权限；为避免顶栏频繁
     * 请求重型聚合，本接口只调 todoService，不再复用 overview。</p>
     *
     * @return 顶栏弹层数据：前 8 条待办和总待办数量
     */
    @GetMapping("/notifications")
    @Operation(summary = "获取顶栏通知铃铛摘要")
    public Result<DashboardNotificationPopoverVO> notifications() {
        StpUtil.checkPermission("dashboard:notifications:query");
        log.info("加载顶栏通知铃铛摘要");
        return Result.ok(notificationService.popover());
    }
}
