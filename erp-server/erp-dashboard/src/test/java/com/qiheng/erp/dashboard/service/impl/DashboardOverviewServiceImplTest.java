package com.qiheng.erp.dashboard.service.impl;

import com.qiheng.erp.dashboard.domain.overview.enums.DashboardAccessState;
import com.qiheng.erp.dashboard.domain.orderstage.model.DashboardOrderStageSnapshot;
import com.qiheng.erp.dashboard.domain.overview.vo.DashboardOverviewVO;
import com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoItemVO;
import com.qiheng.erp.dashboard.loader.DashboardMetricsLoader;
import com.qiheng.erp.dashboard.loader.DashboardOrderStageLoader;
import com.qiheng.erp.dashboard.loader.DashboardStockAlertLoader;
import com.qiheng.erp.dashboard.loader.DashboardSupplierPerformanceLoader;
import com.qiheng.erp.dashboard.loader.DashboardTopProductLoader;
import com.qiheng.erp.dashboard.loader.DashboardTrendLoader;
import com.qiheng.erp.dashboard.permission.DashboardPermissionGuard;
import com.qiheng.erp.dashboard.service.IDashboardTodoService;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作台 overview 聚合测试,主要覆盖 pendingCount 累加和 access.todos 状态分支。
 *
 * <p>{@code /dashboard/notifications} 接口已下线,顶栏铃铛直接复用本聚合的
 * {@code pendingCount} 与 {@code todos},因此本测试同时校验铃铛数据源契约。</p>
 */
class DashboardOverviewServiceImplTest {

    private DashboardPermissionGuard permissionGuard;
    private DashboardMetricsLoader metricsLoader;
    private DashboardTrendLoader trendLoader;
    private IDashboardTodoService todoService;
    private DashboardStockAlertLoader stockAlertLoader;
    private DashboardOrderStageLoader orderStageLoader;
    private DashboardTopProductLoader topProductLoader;
    private DashboardSupplierPerformanceLoader supplierPerformanceLoader;
    private MockedStatic<UserContext> userContextMock;

    @BeforeEach
    void setUp() {
        permissionGuard = mock(DashboardPermissionGuard.class);
        metricsLoader = mock(DashboardMetricsLoader.class);
        trendLoader = mock(DashboardTrendLoader.class);
        todoService = mock(IDashboardTodoService.class);
        stockAlertLoader = mock(DashboardStockAlertLoader.class);
        orderStageLoader = mock(DashboardOrderStageLoader.class);
        topProductLoader = mock(DashboardTopProductLoader.class);
        supplierPerformanceLoader = mock(DashboardSupplierPerformanceLoader.class);
        userContextMock = org.mockito.Mockito.mockStatic(UserContext.class);
        userContextMock.when(UserContext::getCurrentUser).thenReturn(new LoginUser());
        // orderStageLoader 默认返回空快照,避免 NPE
        org.mockito.Mockito.lenient().when(orderStageLoader.load(any()))
                .thenReturn(new DashboardOrderStageSnapshot(null, List.of()));
    }

    @AfterEach
    void tearDown() {
        userContextMock.close();
    }

    @Test
    void shouldAccumulatePendingCountFromTodos() {
        when(permissionGuard.canViewSales(any())).thenReturn(true);
        when(permissionGuard.canViewPurchase(any())).thenReturn(true);
        when(permissionGuard.canViewWarehouse(any())).thenReturn(true);
        when(permissionGuard.canViewSupplier(any())).thenReturn(true);
        when(permissionGuard.canViewSystemException(any())).thenReturn(false);
        when(permissionGuard.canManagePurchase(any())).thenReturn(true);
        when(permissionGuard.canManageSales(any())).thenReturn(true);
        when(permissionGuard.canManageWarehouse(any())).thenReturn(true);
        when(permissionGuard.canViewTrend(any())).thenReturn(false);
        when(metricsLoader.load(any())).thenReturn(List.of());
        when(todoService.loadTodos(any())).thenReturn(List.of(
                todoWithCount(3), todoWithCount(5), todoWithCount(null), todoWithCount(2)
        ));

        DashboardOverviewServiceImpl service = newService();
        DashboardOverviewVO vo = service.overview();

        // 3 + 5 + 2 = 10,null 跳过
        assertThat(vo.getPendingCount()).isEqualTo(10);
    }

    @Test
    void shouldReturnZeroPendingCountWhenNoTodos() {
        when(permissionGuard.canViewSales(any())).thenReturn(false);
        when(permissionGuard.canViewPurchase(any())).thenReturn(false);
        when(permissionGuard.canViewWarehouse(any())).thenReturn(false);
        when(permissionGuard.canViewSupplier(any())).thenReturn(false);
        when(permissionGuard.canViewSystemException(any())).thenReturn(false);
        when(permissionGuard.canManagePurchase(any())).thenReturn(false);
        when(permissionGuard.canManageSales(any())).thenReturn(false);
        when(permissionGuard.canManageWarehouse(any())).thenReturn(false);
        when(permissionGuard.canViewTrend(any())).thenReturn(false);
        when(metricsLoader.load(any())).thenReturn(List.of());
        when(todoService.loadTodos(any())).thenReturn(List.of());

        DashboardOverviewServiceImpl service = newService();
        DashboardOverviewVO vo = service.overview();

        assertThat(vo.getPendingCount()).isEqualTo(0);
        assertThat(vo.getAccess().getTodos().getState()).isEqualTo(DashboardAccessState.DENIED);
    }

    @Test
    void shouldExposePendingCountFieldForBellReuse() {
        // 铃铛数据源契约:overview.pendingCount 字段必须存在并可被序列化。
        when(permissionGuard.canViewSales(any())).thenReturn(true);
        when(permissionGuard.canViewPurchase(any())).thenReturn(true);
        when(permissionGuard.canViewWarehouse(any())).thenReturn(true);
        when(permissionGuard.canViewSupplier(any())).thenReturn(true);
        when(permissionGuard.canViewSystemException(any())).thenReturn(true);
        when(permissionGuard.canManagePurchase(any())).thenReturn(true);
        when(permissionGuard.canManageSales(any())).thenReturn(true);
        when(permissionGuard.canManageWarehouse(any())).thenReturn(true);
        when(permissionGuard.canViewTrend(any())).thenReturn(false);
        when(metricsLoader.load(any())).thenReturn(List.of());
        when(todoService.loadTodos(any())).thenReturn(List.of(todoWithCount(7)));

        DashboardOverviewServiceImpl service = newService();
        DashboardOverviewVO vo = service.overview();

        assertThat(vo.getPendingCount()).isNotNull();
        assertThat(vo.getAccess().getTodos()).isNotNull();
    }

    @Test
    void todoItemShouldExtendSummaryVo() {
        // 继承关系契约:铃铛只读父类 7 字段,工作台读完整字段。
        assertThat(com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoSummaryVO.class)
                .isAssignableFrom(DashboardTodoItemVO.class);
    }

    private DashboardOverviewServiceImpl newService() {
        return new DashboardOverviewServiceImpl(
                permissionGuard, metricsLoader, trendLoader, todoService,
                stockAlertLoader, orderStageLoader, topProductLoader, supplierPerformanceLoader
        );
    }

    private DashboardTodoItemVO todoWithCount(Integer count) {
        DashboardTodoItemVO todo = new DashboardTodoItemVO();
        todo.setTodoId("todo-" + System.nanoTime());
        todo.setBusinessType("SALES");
        todo.setBusinessLabel("销售");
        todo.setTitle("测试待办");
        todo.setDescription("用于测试 pendingCount 累加");
        todo.setCount(count);
        todo.setPriority("MEDIUM");
        todo.setSortWeight(50);
        todo.setCompletionMode("AUTO");
        todo.setDetail(null);
        return todo;
    }
}