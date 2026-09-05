package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.Wrapper;

import com.qiheng.erp.dashboard.cache.PrevValueCache;
import com.qiheng.erp.dashboard.cache.model.PendingOrderSnapshot;
import com.qiheng.erp.dashboard.domain.vo.DashboardMetricVO;
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
                outboundBillMapper, returnOrderMapper, stockAlertLoader, prevValueCache).load(user);

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
                outboundBillMapper, returnOrderMapper, stockAlertLoader, prevValueCache).load(user);

        DashboardMetricVO pendingMetric = metrics.get(2);
        assertThat(pendingMetric.getValue()).isEqualByComparingTo("10");
        assertThat(pendingMetric.getChangeRate()).isEqualByComparingTo("42.86");
        assertThat(pendingMetric.getCompareText()).isEqualTo("较昨日");
        assertThat(pendingMetric.getStatus().name()).isEqualTo("RISK");
    }
}