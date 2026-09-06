package com.qiheng.erp.dashboard.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.dashboard.domain.vo.DashboardNotificationPopoverVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardOverviewVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardInventoryStatusVO;
import com.qiheng.erp.dashboard.job.DashboardDailySnapshotJob;
import com.qiheng.erp.dashboard.job.DashboardMonthlySnapshotJob;
import com.qiheng.erp.dashboard.service.IDashboardNotificationService;
import com.qiheng.erp.dashboard.service.IDashboardOverviewService;
import com.qiheng.erp.dashboard.loader.DashboardInventoryStatusLoader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final DashboardInventoryStatusLoader inventoryStatusLoader;
    // 顶栏通知服务
    private final IDashboardNotificationService notificationService;
    // 日快照任务（手动触发用）
    private final DashboardDailySnapshotJob dailySnapshotJob;
    // 月快照任务（手动触发用）
    private final DashboardMonthlySnapshotJob monthlySnapshotJob;

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

    @GetMapping("/inventory-status")
    @Operation(summary = "Get dashboard inventory status")
    public Result<DashboardInventoryStatusVO> inventoryStatus(@RequestParam(required = false) Long warehouseId) {
        StpUtil.checkPermission("dashboard:overview:query");
        return Result.ok(inventoryStatusLoader.load(warehouseId));
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

    /**
     * 手动触发工作台快照任务（日快照 + 月快照）
     *
     * <p>仅限管理岗调试使用；定时任务由 @Scheduled 自动执行，本接口用于
     * 首次部署 / 缓存丢失 / 调度异常时人工补齐快照。幂等可重复调用。</p>
     *
     * @return 固定 success
     */
    @PostMapping("/snapshot/trigger")
    @Operation(summary = "手动触发工作台快照任务")
    public Result<Void> triggerSnapshot() {
        StpUtil.checkPermission("dashboard:overview:query");
        log.info("手动触发工作台快照任务");
        dailySnapshotJob.snapshot();
        // 手动触发时拍当月快照（本月尚未结束，拍的是截至此刻的累计；月末调度会覆盖为完整值）
        monthlySnapshotJob.snapshotOfMonth(java.time.YearMonth.now());
        return Result.ok();
    }
}
