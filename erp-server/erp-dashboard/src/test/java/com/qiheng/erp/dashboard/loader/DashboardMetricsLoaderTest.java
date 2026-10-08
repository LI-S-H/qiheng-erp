package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.dashboard.domain.metric.enums.MetricComparisonState;

import com.qiheng.erp.dashboard.cache.PrevValueCache;
import com.qiheng.erp.dashboard.cache.model.PendingOrderSnapshot;
import com.qiheng.erp.dashboard.domain.metric.vo.DashboardMetricVO;
import com.qiheng.erp.dashboard.permission.DashboardPermissionGuard;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import com.qiheng.erp.warehouse.mapper.OutboundBillMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardMetricsLoaderTest {

    @Test
    void shouldAggregateMonthlyAmountsInDatabaseForCurrentAndPreviousMonth() {
        DashboardPermissionGuard permissionGuard = mock(DashboardPermissionGuard.class);
        SalesOrderMapper salesOrderMapper = mock(SalesOrderMapper.class);
        PurchaseOrderMapper purchaseOrderMapper = mock(PurchaseOrderMapper.class);
        InboundBillMapper inboundBillMapper = mock(InboundBillMapper.class);
        OutboundBillMapper outboundBillMapper = mock(OutboundBillMapper.class);
        ReturnOrderMapper returnOrderMapper = mock(ReturnOrderMapper.class);
        DashboardStockAlertLoader stockAlertLoader = mock(DashboardStockAlertLoader.class);
        PrevValueCache prevValueCache = mock(PrevValueCache.class);
        LoginUser user = new LoginUser();

        when(permissionGuard.canViewSales(user)).thenReturn(true);
        when(permissionGuard.canViewPurchase(user)).thenReturn(true);
        when(permissionGuard.canViewWarehouse(user)).thenReturn(true);
        when(salesOrderMapper.selectObjs(ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<SalesOrder>>any())).thenReturn(List.of((Object) 10_000L));
        when(purchaseOrderMapper.selectObjs(ArgumentMatchers.<com.baomidou.mybatisplus.core.conditions.Wrapper<PurchaseOrder>>any())).thenReturn(List.of((Object) 4_000L));
        when(stockAlertLoader.countRiskSkus()).thenReturn(0);
        when(returnOrderMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        List<DashboardMetricVO> metrics = new DashboardMetricsLoader(
                permissionGuard, salesOrderMapper, purchaseOrderMapper, inboundBillMapper,
                outboundBillMapper, returnOrderMapper, stockAlertLoader, prevValueCache,
                mock(SystemExceptionMqPublisher.class)).load(user);

        assertThat(metrics.getFirst().getValue()).isEqualByComparingTo("100");
        assertThat(metrics.get(1).getValue()).isEqualByComparingTo("60");
        verify(salesOrderMapper, times(2)).selectObjs(any(Wrapper.class));
        verify(purchaseOrderMapper, times(2)).selectObjs(any(Wrapper.class));
    }
    @Test
    void shouldComparePendingOrdersWithinCurrentPermissionScope() {
        DashboardPermissionGuard permissionGuard = mock(DashboardPermissionGuard.class);
        SalesOrderMapper salesOrderMapper = mock(SalesOrderMapper.class);
        PurchaseOrderMapper purchaseOrderMapper = mock(PurchaseOrderMapper.class);
        InboundBillMapper inboundBillMapper = mock(InboundBillMapper.class);
        OutboundBillMapper outboundBillMapper = mock(OutboundBillMapper.class);
        ReturnOrderMapper returnOrderMapper = mock(ReturnOrderMapper.class);
        DashboardStockAlertLoader stockAlertLoader = mock(DashboardStockAlertLoader.class);
        PrevValueCache prevValueCache = mock(PrevValueCache.class);
        LoginUser user = new LoginUser();

        when(permissionGuard.canViewSales(user)).thenReturn(false);
        when(permissionGuard.canViewPurchase(user)).thenReturn(true);
        when(permissionGuard.canViewWarehouse(user)).thenReturn(true);
        when(purchaseOrderMapper.selectObjs(ArgumentMatchers.<Wrapper<PurchaseOrder>>any())).thenReturn(List.of((Object) 0L));
        when(purchaseOrderMapper.selectCount(ArgumentMatchers.<Wrapper<PurchaseOrder>>any())).thenReturn(4L);
        when(inboundBillMapper.selectCount(any())).thenReturn(3L);
        when(outboundBillMapper.selectCount(any())).thenReturn(2L);
        when(stockAlertLoader.countRiskSkus()).thenReturn(0);
        when(returnOrderMapper.selectCount(any(Wrapper.class))).thenReturn(1L);
        when(prevValueCache.getPendingOrderSnapshot(any())).thenReturn(new PendingOrderSnapshot(2, 1, 7, 0, 3, 1));

        List<DashboardMetricVO> metrics = new DashboardMetricsLoader(
                permissionGuard, salesOrderMapper, purchaseOrderMapper, inboundBillMapper,
                outboundBillMapper, returnOrderMapper, stockAlertLoader, prevValueCache,
                mock(SystemExceptionMqPublisher.class)).load(user);

        DashboardMetricVO pendingMetric = metrics.get(2);
        assertThat(pendingMetric.getValue()).isEqualByComparingTo("10");
        assertThat(pendingMetric.getChangeRate()).isEqualByComparingTo("42.86");
        assertThat(pendingMetric.getCompareText()).isEqualTo("较昨日");
        assertThat(pendingMetric.getStatus().name()).isEqualTo("RISK");
    }

    @Test
    void shouldRecalculateAfterRedisReadFailureAndKeepResultWhenCacheWriteFails() {
        Fixture f = new Fixture();
        when(f.cache.getMonthlySnapshot(any(), any())).thenThrow(new IllegalStateException("Redis 读取失败"));
        org.mockito.Mockito.doThrow(new IllegalStateException("Redis 写入失败"))
                .when(f.cache).putMonthlySnapshot(any(), any(), any());
        var metrics = f.loader.load(f.user);
        assertThat(metrics.getFirst().getValue()).isEqualByComparingTo("100");
        assertThat(metrics.getFirst().getChangeRate()).isEqualByComparingTo("0");
        assertThat(metrics.getFirst().getComparisonState()).isEqualTo(MetricComparisonState.AVAILABLE);
        verify(f.sales, times(2)).selectObjs(any(Wrapper.class));
    }

    @Test
    void shouldKeepCurrentValueAndExposeUnavailableWhenPreviousMonthQueryFails() {
        Fixture f = new Fixture();
        when(f.sales.selectObjs(ArgumentMatchers.<Wrapper<SalesOrder>>any()))
                .thenReturn(List.of((Object) 10_000L)).thenThrow(new IllegalStateException("上月查询失败"));
        var metrics = f.loader.load(f.user);
        assertThat(metrics.getFirst().getValue()).isEqualByComparingTo("100");
        assertThat(metrics.get(1).getValue()).isEqualByComparingTo("60");
        assertThat(metrics.getFirst().getChangeRate()).isNull();
        assertThat(metrics.getFirst().getComparisonState()).isEqualTo(MetricComparisonState.UNAVAILABLE);
        assertThat(metrics.get(1).getComparisonState()).isEqualTo(MetricComparisonState.UNAVAILABLE);
        verify(f.publisher).publishSystemError(any());
    }

    @Test
    void shouldDistinguishZeroBaselineFromReadFailure() {
        Fixture f = new Fixture();
        when(f.cache.getMonthlySnapshot(any(), any())).thenReturn(java.math.BigDecimal.ZERO);
        var metrics = f.loader.load(f.user);
        assertThat(metrics.getFirst().getChangeRate()).isNull();
        assertThat(metrics.getFirst().getComparisonState()).isEqualTo(MetricComparisonState.NO_BASELINE);
        assertThat(metrics.get(2).getComparisonState()).isEqualTo(MetricComparisonState.NO_BASELINE);
        org.mockito.Mockito.verifyNoInteractions(f.publisher);
    }

    @Test
    void shouldKeepOperationalValuesWhenDailyCacheReadFails() {
        Fixture f = new Fixture();
        when(f.cache.getPendingOrderSnapshot(any())).thenThrow(new IllegalStateException("日快照读取失败"));
        when(f.cache.getDailySnapshot(any(), any())).thenThrow(new IllegalStateException("日快照读取失败"));
        var metrics = f.loader.load(f.user);
        assertThat(metrics.get(2).getValue()).isEqualByComparingTo("0");
        assertThat(metrics.get(3).getValue()).isEqualByComparingTo("0");
        assertThat(metrics.get(2).getComparisonState()).isEqualTo(MetricComparisonState.UNAVAILABLE);
        assertThat(metrics.get(3).getComparisonState()).isEqualTo(MetricComparisonState.UNAVAILABLE);
    }

    @Test
    void shouldNotExposeComparisonStateOrQueryBaselineWithoutPermissions() {
        Fixture f = new Fixture();
        when(f.guard.canViewSales(f.user)).thenReturn(false);
        when(f.guard.canViewPurchase(f.user)).thenReturn(false);
        when(f.guard.canViewWarehouse(f.user)).thenReturn(false);
        assertThat(f.loader.load(f.user)).allSatisfy(metric -> {
            assertThat(metric.getValue()).isNull();
            assertThat(metric.getComparisonState()).isNull();
        });
        org.mockito.Mockito.verifyNoInteractions(f.cache, f.publisher);
    }

    /** 故障测试使用独立 mock，避免一条测试的异常状态污染其他场景。 */
    private static class Fixture {
        final LoginUser user = new LoginUser();
        final DashboardPermissionGuard guard = mock(DashboardPermissionGuard.class);
        final SalesOrderMapper sales = mock(SalesOrderMapper.class);
        final PurchaseOrderMapper purchases = mock(PurchaseOrderMapper.class);
        final PrevValueCache cache = mock(PrevValueCache.class);
        final SystemExceptionMqPublisher publisher = mock(SystemExceptionMqPublisher.class);
        final DashboardMetricsLoader loader;

        Fixture() {
            when(guard.canViewSales(user)).thenReturn(true);
            when(guard.canViewPurchase(user)).thenReturn(true);
            when(guard.canViewWarehouse(user)).thenReturn(true);
            when(sales.selectObjs(ArgumentMatchers.<Wrapper<SalesOrder>>any())).thenReturn(List.of((Object) 10_000L));
            when(purchases.selectObjs(ArgumentMatchers.<Wrapper<PurchaseOrder>>any())).thenReturn(List.of((Object) 4_000L));
            loader = new DashboardMetricsLoader(guard, sales, purchases, mock(InboundBillMapper.class),
                    mock(OutboundBillMapper.class), mock(ReturnOrderMapper.class),
                    mock(DashboardStockAlertLoader.class), cache, publisher);
        }
    }
}
