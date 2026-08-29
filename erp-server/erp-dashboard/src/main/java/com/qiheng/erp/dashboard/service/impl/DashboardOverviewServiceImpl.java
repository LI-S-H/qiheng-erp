package com.qiheng.erp.dashboard.service.impl;

import com.qiheng.erp.dashboard.domain.vo.DashboardMetricVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardOrderStagePermissionsVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardOverviewVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardStockAlertVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardTodoItemVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardTopProductVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardTrendPermissionsVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardTrendPointVO;
import com.qiheng.erp.dashboard.loader.DashboardMetricsLoader;
import com.qiheng.erp.dashboard.loader.DashboardOrderStageLoader;
import com.qiheng.erp.dashboard.loader.DashboardStockAlertLoader;
import com.qiheng.erp.dashboard.loader.DashboardSupplierPerformanceLoader;
import com.qiheng.erp.dashboard.loader.DashboardTopProductLoader;
import com.qiheng.erp.dashboard.loader.DashboardTrendLoader;
import com.qiheng.erp.dashboard.permission.DashboardPermissionGuard;
import com.qiheng.erp.dashboard.service.IDashboardOverviewService;
import com.qiheng.erp.dashboard.service.IDashboardTodoService;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工作台经营概览服务实现。
 *
 * <p>按当前用户权限裁剪各 loader：
 * <ul>
 *   <li>大汇总 4 个指标：按业务子项权限在 loader 内部归 0</li>
 *   <li>经营趋势：完全无权时返回空 trend + trendPermissions 全 false，前端降级</li>
 *   <li>库存风险 SKU：按 warehouse:query 裁剪</li>
 *   <li>订单流转：完全无权时返回空 + orderStagePermissions 全 false，前端降级</li>
 *   <li>销售商品排行 / 供应商履约：按对应模块 query 裁剪</li>
 * </ul>
 *
 * <p>所有待办统一由 {@link IDashboardTodoService} 聚合，避免与顶栏铃铛重复实现。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Service
@RequiredArgsConstructor
public class DashboardOverviewServiceImpl implements IDashboardOverviewService {
    // 权限判断卫
    private final DashboardPermissionGuard permissionGuard;
    // 4个首屏指标（始终返回）加载器
    private final DashboardMetricsLoader metricsLoader;
    // 近30天趋势（需销售/采购权限）加载器
    private final DashboardTrendLoader trendLoader;
    // 待办聚合服务
    private final IDashboardTodoService todoService;
    // 库存风险SKU（需仓库权限）加载器
    private final DashboardStockAlertLoader stockAlertLoader;
    // 订单流转阶段（需销售/采购权限）加载器
    private final DashboardOrderStageLoader orderStageLoader;
    // 销售商品排行（需销售权限）加载器
    private final DashboardTopProductLoader topProductLoader;
    // 供应商履约（需供应商权限）加载器
    private final DashboardSupplierPerformanceLoader supplierPerformanceLoader;

    /**
     * 加载工作台经营概览
     *
     * @return 工作台概览 VO
     */
    @Override
    public DashboardOverviewVO overview() {
        LoginUser user = UserContext.getCurrentUser();

        DashboardOverviewVO vo = new DashboardOverviewVO();
        vo.setRefreshedAt(LocalDateTime.now());

        // 首屏指标：始终返回 4 个固定指标，按用户权限内部归 0
        vo.setMetrics(metricsLoader.load(user));

        // 趋势：仅当拥有销售或采购权限时返回；否则返回空 trend + permissions 全 false 触发前端降级
        DashboardTrendPermissionsVO trendPerm = new DashboardTrendPermissionsVO();
        boolean canViewSales = permissionGuard.canViewSales(user);
        boolean canViewPurchase = permissionGuard.canViewPurchase(user);
        trendPerm.setCanViewSales(canViewSales);
        trendPerm.setCanViewPurchase(canViewPurchase);
        trendPerm.setCanViewGross(canViewSales && canViewPurchase);
        vo.setTrendPermissions(trendPerm);
        if (permissionGuard.canViewTrend(user)) {
            List<DashboardTrendPointVO> trendPoints = trendLoader.load(user);
            vo.setTrend(trendPoints);
        } else {
            vo.setTrend(List.of());
        }

        // 待办：由 todoService 统一聚合，按权限裁剪业务类 + 系统异常
        vo.setTodos(todoService.loadTodos(user));

        // 库存风险：按仓库权限裁剪
        if (permissionGuard.canViewWarehouse(user)) {
            vo.setStockAlerts(stockAlertLoader.load());
        } else {
            vo.setStockAlerts(List.of());
        }

        // 订单流转：完全无权时整段降级
        DashboardOrderStagePermissionsVO orderPerm = new DashboardOrderStagePermissionsVO();
        orderPerm.setCanViewPurchase(canViewPurchase);
        orderPerm.setCanViewSales(canViewSales);
        vo.setOrderStagePermissions(orderPerm);
        if (canViewPurchase || canViewSales) {
            vo.setOrderStages(orderStageLoader.load(user));
        } else {
            vo.setOrderStages(List.of());
        }

        // 销售商品排行：按销售权限裁剪
        if (canViewSales) {
            List<DashboardTopProductVO> topProducts = topProductLoader.load();
            vo.setTopProducts(topProducts);
        } else {
            vo.setTopProducts(List.of());
        }

        // 供应商履约：按供应商权限裁剪
        if (permissionGuard.canViewSupplier(user)) {
            vo.setSupplierPerformance(supplierPerformanceLoader.load());
        } else {
            vo.setSupplierPerformance(List.of());
        }
        return vo;
    }
}
