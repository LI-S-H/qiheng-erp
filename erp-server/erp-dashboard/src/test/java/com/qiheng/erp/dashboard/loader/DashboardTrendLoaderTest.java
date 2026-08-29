package com.qiheng.erp.dashboard.loader;

import com.qiheng.erp.dashboard.domain.vo.DashboardTrendPointVO;
import com.qiheng.erp.dashboard.permission.DashboardPermissionGuard;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.security.domain.dto.LoginUser;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardTrendLoaderTest {

    @Test
    void shouldKeepNegativeGrossMarginForLossDay() {
        SalesOrderMapper salesOrderMapper = mock(SalesOrderMapper.class);
        PurchaseOrderMapper purchaseOrderMapper = mock(PurchaseOrderMapper.class);
        DashboardPermissionGuard permissionGuard = mock(DashboardPermissionGuard.class);
        LoginUser user = new LoginUser();
        when(permissionGuard.canViewSales(Mockito.any())).thenReturn(true);
        when(permissionGuard.canViewPurchase(Mockito.any())).thenReturn(true);

        LocalDateTime approvedAt = LocalDate.now().atTime(12, 0);

        SalesOrder salesOrder = new SalesOrder();
        salesOrder.setApprovedAt(approvedAt);
        salesOrder.setTotalAmount(10000L);
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.setApprovedAt(approvedAt);
        purchaseOrder.setTotalAmount(16000L);

        when(salesOrderMapper.selectList(any())).thenReturn(List.of(salesOrder));
        when(purchaseOrderMapper.selectList(any())).thenReturn(List.of(purchaseOrder));

        List<DashboardTrendPointVO> trend = new DashboardTrendLoader(permissionGuard, salesOrderMapper, purchaseOrderMapper).load(user);

        DashboardTrendPointVO today = trend.get(trend.size() - 1);
        assertThat(today.getDate()).isEqualTo(LocalDate.now());
        assertThat(today.getGrossMarginAmount()).isEqualByComparingTo(new BigDecimal("-60"));
    }
}
