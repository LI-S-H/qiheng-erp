package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.qiheng.erp.dashboard.domain.orderstage.enums.DashboardOrderStagePeriodType;
import com.qiheng.erp.dashboard.domain.orderstage.vo.DashboardOrderStageVO;
import com.qiheng.erp.dashboard.permission.DashboardPermissionGuard;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.enums.SalesOrderStatus;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.security.domain.dto.LoginUser;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardOrderStageLoaderTest {

    @BeforeEach
    void setUp() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PurchaseOrder.class);
        TableInfoHelper.initTableInfo(assistant, SalesOrder.class);
    }

    @Test
    void shouldGroupCurrentMonthOrdersByCurrentStatus() {
        DashboardPermissionGuard permissionGuard = mock(DashboardPermissionGuard.class);
        PurchaseOrderMapper purchaseOrderMapper = mock(PurchaseOrderMapper.class);
        SalesOrderMapper salesOrderMapper = mock(SalesOrderMapper.class);
        LoginUser user = new LoginUser();
        when(permissionGuard.canViewPurchase(user)).thenReturn(true);
        when(permissionGuard.canViewSales(user)).thenReturn(true);
        when(purchaseOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                purchase(PurchaseOrderStatus.DRAFT), purchase(PurchaseOrderStatus.INBOUND_DONE)));
        when(salesOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                sales(SalesOrderStatus.SUBMITTED), sales(SalesOrderStatus.PARTIAL_OUTBOUND)));

        var snapshot = new DashboardOrderStageLoader(
                permissionGuard, purchaseOrderMapper, salesOrderMapper, dashboardClock()).load(user);
        List<DashboardOrderStageVO> stages = snapshot.stages();

        assertThat(snapshot.period().getType()).isEqualTo(DashboardOrderStagePeriodType.CURRENT_CALENDAR_MONTH);
        assertThat(snapshot.period().getStartAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
        assertThat(snapshot.period().getEndAtExclusive()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));

        assertThat(stages).hasSize(6);
        assertThat(stages.get(0).getPurchaseCount()).isEqualTo(1);
        assertThat(stages.get(0).getSalesCount()).isZero();
        assertThat(stages.get(1).getPurchaseCount()).isZero();
        assertThat(stages.get(1).getSalesCount()).isEqualTo(1);
        assertThat(stages.get(3).getPurchaseCount()).isZero();
        assertThat(stages.get(3).getSalesCount()).isEqualTo(1);
        assertThat(stages.get(4).getPurchaseCount()).isEqualTo(1);
        assertThat(stages.get(5).getPurchaseCount()).isZero();
        assertThat(stages.get(5).getSalesCount()).isZero();

        ArgumentCaptor<Wrapper<PurchaseOrder>> purchaseQuery = ArgumentCaptor.forClass(Wrapper.class);
        ArgumentCaptor<Wrapper<SalesOrder>> salesQuery = ArgumentCaptor.forClass(Wrapper.class);
        verify(purchaseOrderMapper).selectList(purchaseQuery.capture());
        verify(salesOrderMapper).selectList(salesQuery.capture());
        assertThat(purchaseQuery.getValue().getSqlSegment()).contains("create_time").contains(">=").contains("<");
        assertThat(salesQuery.getValue().getSqlSegment()).contains("create_time").contains(">=").contains("<");
    }

    @Test
    void shouldNotQuerySalesOrdersWithoutSalesPermission() {
        DashboardPermissionGuard permissionGuard = mock(DashboardPermissionGuard.class);
        PurchaseOrderMapper purchaseOrderMapper = mock(PurchaseOrderMapper.class);
        SalesOrderMapper salesOrderMapper = mock(SalesOrderMapper.class);
        LoginUser user = new LoginUser();
        when(permissionGuard.canViewPurchase(user)).thenReturn(true);
        when(permissionGuard.canViewSales(user)).thenReturn(false);
        when(purchaseOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(purchase(PurchaseOrderStatus.SUBMITTED)));

        var snapshot = new DashboardOrderStageLoader(
                permissionGuard, purchaseOrderMapper, salesOrderMapper, dashboardClock()).load(user);
        List<DashboardOrderStageVO> stages = snapshot.stages();

        assertThat(stages.get(1).getPurchaseCount()).isEqualTo(1);
        assertThat(stages).allSatisfy(stage -> assertThat(stage.getSalesCount()).isZero());
        verify(salesOrderMapper, never()).selectList(any(Wrapper.class));
    }

    private static Clock dashboardClock() {
        return Clock.fixed(Instant.parse("2026-09-03T16:00:00Z"), ZoneId.of("Asia/Shanghai"));
    }

    private static PurchaseOrder purchase(PurchaseOrderStatus status) {
        return new PurchaseOrder().setStatus(status.name());
    }

    private static SalesOrder sales(SalesOrderStatus status) {
        return new SalesOrder().setStatus(status.name());
    }
}