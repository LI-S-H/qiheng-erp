package com.qiheng.erp.dashboard.service.impl;

import com.qiheng.erp.dashboard.domain.vo.DashboardMetricVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardOrderStageVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardOverviewVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardStockAlertVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardSupplierPerformanceVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardTodoItemVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardTopProductVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardTrendPointVO;
import com.qiheng.erp.dashboard.loader.DashboardMetricsLoader;
import com.qiheng.erp.dashboard.loader.DashboardOrderStageLoader;
import com.qiheng.erp.dashboard.loader.DashboardStockAlertLoader;
import com.qiheng.erp.dashboard.loader.DashboardSupplierPerformanceLoader;
import com.qiheng.erp.dashboard.loader.DashboardSystemExceptionLoader;
import com.qiheng.erp.dashboard.loader.DashboardTodoAggregator;
import com.qiheng.erp.dashboard.loader.DashboardTopProductLoader;
import com.qiheng.erp.dashboard.loader.DashboardTrendLoader;
import com.qiheng.erp.dashboard.permission.DashboardPermissionGuard;
import com.qiheng.erp.dashboard.service.IDashboardOverviewService;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 工作台经营概览服务实现。
 *
 * <p>按当前用户权限裁剪 8 个 loader：<br>
 * 1. {@link DashboardPermissionGuard} 判断可访问的模块；<br>
 * 2. 无权模块的指标/待办/列表返回空数组或 0 值；<br>
 * 3. 全部待办按 sortWeight 升序合并，刷新时间统一为当前时间。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Service
public class DashboardOverviewServiceImpl implements IDashboardOverviewService {

    private final DashboardPermissionGuard permissionGuard;
    private final DashboardMetricsLoader metricsLoader;
    private final DashboardTrendLoader trendLoader;
    private final DashboardTodoAggregator todoAggregator;
    private final DashboardSystemExceptionLoader systemExceptionLoader;
    private final DashboardStockAlertLoader stockAlertLoader;
    private final DashboardOrderStageLoader orderStageLoader;
    private final DashboardTopProductLoader topProductLoader;
    private final DashboardSupplierPerformanceLoader supplierPerformanceLoader;

    @Autowired
    public DashboardOverviewServiceImpl(DashboardPermissionGuard permissionGuard,
                                       DashboardMetricsLoader metricsLoader,
                                       DashboardTrendLoader trendLoader,
                                       DashboardTodoAggregator todoAggregator,
                                       DashboardSystemExceptionLoader systemExceptionLoader,
                                       DashboardStockAlertLoader stockAlertLoader,
                                       DashboardOrderStageLoader orderStageLoader,
                                       DashboardTopProductLoader topProductLoader,
                                       DashboardSupplierPerformanceLoader supplierPerformanceLoader) {
        this.permissionGuard = permissionGuard;
        this.metricsLoader = metricsLoader;
        this.trendLoader = trendLoader;
        this.todoAggregator = todoAggregator;
        this.systemExceptionLoader = systemExceptionLoader;
        this.stockAlertLoader = stockAlertLoader;
        this.orderStageLoader = orderStageLoader;
        this.topProductLoader = topProductLoader;
        this.supplierPerformanceLoader = supplierPerformanceLoader;
    }

    /**
     * 加载工作台经营概览
     * @return 工作台概览 VO
     */
    @Override
    public DashboardOverviewVO overview() {
        LoginUser user = UserContext.getCurrentUser();

        DashboardOverviewVO vo = new DashboardOverviewVO();
        vo.setRefreshedAt(LocalDateTime.now());

        // 首屏指标：始终返回 4 个固定指标，由 loader 内部按权限简化数值
        List<DashboardMetricVO> metrics = metricsLoader.load();
        if (metrics.isEmpty()) {
            vo.setMetrics(new ArrayList<>());
        } else {
            vo.setMetrics(metrics);
        }

        // 趋势：仅当拥有销售或采购权限时返回；否则空
        boolean canViewSales = permissionGuard.canViewSales(user);
        boolean canViewPurchase = permissionGuard.canViewPurchase(user);
        if (canViewSales || canViewPurchase) {
            vo.setTrend(trendLoader.load());
        } else {
            vo.setTrend(new ArrayList<>());
        }

        // 待办：业务聚合待办按权限裁剪，系统异常按 OpenAPI 描述始终对 dashboard 权限持有者返回
        List<DashboardTodoItemVO> todos = new ArrayList<>();
        if (canViewPurchase) {
            todos.addAll(todoAggregator.load()); // 内部已聚合 7 类
        }
        // 系统异常独立返回（不依赖业务权限）
        DashboardTodoItemVO systemTodo = systemExceptionLoader.load();
        if (systemTodo != null && systemTodo.getCount() != null && systemTodo.getCount() > 0) {
            todos.add(systemTodo);
        }
        todos.sort(Comparator.comparingInt(DashboardTodoItemVO::getSortWeight));
        vo.setTodos(todos);

        // 库存风险：按仓库权限裁剪
        if (permissionGuard.canViewWarehouse(user)) {
            vo.setStockAlerts(stockAlertLoader.load());
        } else {
            vo.setStockAlerts(new ArrayList<>());
        }

        // 订单流转：按采购和销售权限裁剪
        if (canViewPurchase || canViewSales) {
            vo.setOrderStages(orderStageLoader.load());
        } else {
            vo.setOrderStages(new ArrayList<>());
        }

        // 销售商品排行：按销售权限裁剪
        if (canViewSales) {
            vo.setTopProducts(topProductLoader.load());
        } else {
            vo.setTopProducts(new ArrayList<>());
        }

        // 供应商履约：按供应商权限裁剪
        if (permissionGuard.canViewSupplier(user)) {
            vo.setSupplierPerformance(supplierPerformanceLoader.load());
        } else {
            vo.setSupplierPerformance(new ArrayList<>());
        }
        return vo;
    }
}