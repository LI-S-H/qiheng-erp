package com.qiheng.erp.dashboard.service.impl;

import com.qiheng.erp.dashboard.domain.enums.DashboardAccessState;
import com.qiheng.erp.dashboard.domain.enums.MetricKey;
import com.qiheng.erp.dashboard.domain.model.DashboardOrderStageSnapshot;
import com.qiheng.erp.dashboard.domain.vo.DashboardOrderStagePermissionsVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardOrderStageVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardOverviewAccessVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardOverviewVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardSectionAccessVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardStockAlertVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardSupplierPerformanceVO;
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
 * <p>经营趋势保留既有权限契约；指标和其余业务面板统一通过 {@code access}
 * 区分无权限与暂无数据，避免将权限缺失伪装成业务 0 值。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Service
@RequiredArgsConstructor
public class DashboardOverviewServiceImpl implements IDashboardOverviewService {
    // 工作台权限判断卫士。
    private final DashboardPermissionGuard permissionGuard;
    // 固定返回四项首屏指标的加载器。
    private final DashboardMetricsLoader metricsLoader;
    // 近 30 日经营趋势加载器。
    private final DashboardTrendLoader trendLoader;
    // 统一聚合业务待办，避免和顶栏消息重复实现。
    private final IDashboardTodoService todoService;
    // 库存风险 SKU 加载器。
    private final DashboardStockAlertLoader stockAlertLoader;
    // 采购、销售订单流转阶段加载器。
    private final DashboardOrderStageLoader orderStageLoader;
    // 销售商品排行加载器。
    private final DashboardTopProductLoader topProductLoader;
    // 供应商履约表现加载器。
    private final DashboardSupplierPerformanceLoader supplierPerformanceLoader;

    /**
     * 组装当前用户的工作台数据。
     *
     * <p>经营趋势沿用既有 {@code trendPermissions} 契约；其余区块通过 {@code access}
     * 明确传达 ALLOWED、EMPTY、DENIED。无权限时不再用 0 伪装业务数据。</p>
     */
    @Override
    public DashboardOverviewVO overview() {
        LoginUser user = UserContext.getCurrentUser();
        boolean canViewSales = permissionGuard.canViewSales(user);
        boolean canViewPurchase = permissionGuard.canViewPurchase(user);
        boolean canViewWarehouse = permissionGuard.canViewWarehouse(user);
        boolean canViewSupplier = permissionGuard.canViewSupplier(user);
        boolean canViewException = permissionGuard.canViewSystemException(user);
        boolean canManagePurchase = permissionGuard.canManagePurchase(user);
        boolean canManageSales = permissionGuard.canManageSales(user);
        boolean canManageWarehouse = permissionGuard.canManageWarehouse(user);

        DashboardOverviewVO vo = new DashboardOverviewVO();
        vo.setRefreshedAt(LocalDateTime.now());
        vo.setMetrics(metricsLoader.load(user));

        DashboardOverviewAccessVO access = new DashboardOverviewAccessVO();
        access.getMetrics().put(MetricKey.MONTH_SALES.name(), access(canViewSales, true));
        access.getMetrics().put(MetricKey.MONTH_GROSS_PROFIT.name(), access(canViewSales && canViewPurchase, true));
        boolean canViewPending = canViewSales || canViewPurchase || canViewWarehouse;
        access.getMetrics().put(MetricKey.PENDING_ORDERS.name(), access(canViewPending, true));
        access.getMetrics().put(MetricKey.STOCK_RISK_SKU.name(), access(canViewWarehouse, true));

        // 经营趋势维持现有契约与展示，不纳入本次统一面板改造。
        DashboardTrendPermissionsVO trendPerm = new DashboardTrendPermissionsVO();
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

        List<DashboardTodoItemVO> todos = todoService.loadTodos(user);
        vo.setTodos(todos);
        // 待办展示权限必须与聚合器的处理权限保持一致，避免把“无权”误判为“暂无数据”。
        boolean canViewTodo = canManagePurchase || canManageSales || canManageWarehouse || canViewWarehouse || canViewException;
        access.setTodos(access(canViewTodo, !todos.isEmpty()));

        List<DashboardStockAlertVO> stockAlerts = canViewWarehouse ? stockAlertLoader.load() : List.of();
        vo.setStockAlerts(stockAlerts);
        access.setStockAlerts(access(canViewWarehouse, !stockAlerts.isEmpty()));

        DashboardOrderStagePermissionsVO orderPerm = new DashboardOrderStagePermissionsVO();
        orderPerm.setCanViewPurchase(canViewPurchase);
        orderPerm.setCanViewSales(canViewSales);
        vo.setOrderStagePermissions(orderPerm);
        DashboardOrderStageSnapshot orderStageSnapshot = orderStageLoader.load(user);
        vo.setOrderStagePeriod(orderStageSnapshot.period());
        if (canViewPurchase || canViewSales) {
            vo.setOrderStages(orderStageSnapshot.stages());
        } else {
            vo.setOrderStages(List.of());
        }
        access.setOrderStages(access(canViewPurchase || canViewSales,
                hasVisibleOrderStageData(vo.getOrderStages(), canViewPurchase, canViewSales)));

        List<DashboardTopProductVO> topProducts = canViewSales ? topProductLoader.load() : List.of();
        vo.setTopProducts(topProducts);
        access.setTopProducts(access(canViewSales, !topProducts.isEmpty()));

        List<DashboardSupplierPerformanceVO> supplierPerformance = canViewSupplier
                ? supplierPerformanceLoader.load() : List.of();
        vo.setSupplierPerformance(supplierPerformance);
        access.setSupplierPerformance(access(canViewSupplier, !supplierPerformance.isEmpty()));

        vo.setAccess(access);
        return vo;
    }

    /**
     * 订单流转固定返回阶段行；仅当前账号可见列中存在数量时，面板才有可展示业务数据。
     */
    private static boolean hasVisibleOrderStageData(List<DashboardOrderStageVO> stages,
                                                    boolean canViewPurchase, boolean canViewSales) {
        return stages.stream().anyMatch(stage -> (canViewPurchase && stage.getPurchaseCount() > 0)
                || (canViewSales && stage.getSalesCount() > 0));
    }

    /**
     * 依据当前用户是否具备模块权限以及已裁剪数据，生成统一业务面板状态。
     * 返回数据始终仅包含当前用户获准查看的范围，前端无需再区分完整范围与部分范围。
     */
    private static DashboardSectionAccessVO access(boolean visible, boolean hasData) {
        if (!visible) {
            return DashboardSectionAccessVO.of(DashboardAccessState.DENIED);
        }
        return DashboardSectionAccessVO.of(hasData ? DashboardAccessState.ALLOWED : DashboardAccessState.EMPTY);
    }
}