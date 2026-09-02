package com.qiheng.erp.dashboard.loader;

import com.qiheng.erp.common.event.dashboard.DashboardTrendMetric;
import com.qiheng.erp.dashboard.cache.DashboardTrendDailyAmountRefreshService;
import com.qiheng.erp.dashboard.domain.vo.DashboardTrendPointVO;
import com.qiheng.erp.dashboard.permission.DashboardPermissionGuard;
import com.qiheng.erp.security.domain.dto.LoginUser;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardTrendLoaderTest {

    @Test
    void shouldKeepNegativeGrossMarginForLossDay() {
        Fixture fixture = new Fixture();
        fixture.stubAmounts(10_000L, 16_000L, 0L, 0L);

        DashboardTrendPointVO today = fixture.loadToday();

        assertThat(today.getGrossMarginAmount()).isEqualByComparingTo(new BigDecimal("-60"));
    }

    @Test
    void shouldDeductApprovedReturnAmountsFromNetAmounts() {
        Fixture fixture = new Fixture();
        fixture.stubAmounts(10_000L, 16_000L, 2_000L, 1_000L);

        DashboardTrendPointVO today = fixture.loadToday();

        assertThat(today.getSalesAmount()).isEqualByComparingTo("80");
        assertThat(today.getPurchaseAmount()).isEqualByComparingTo("150");
        assertThat(today.getGrossMarginAmount()).isEqualByComparingTo("-70");
    }

    @Test
    void shouldNotResolvePurchaseMetricsWithoutPurchasePermission() {
        Fixture fixture = new Fixture();
        when(fixture.permissionGuard.canViewPurchase(fixture.user)).thenReturn(false);
        fixture.stubAmounts(10_000L, 16_000L, 0L, 0L);

        DashboardTrendPointVO today = fixture.loadToday();

        assertThat(today.getPurchaseAmount()).isZero();
        assertThat(today.getGrossMarginAmount()).isZero();
        verify(fixture.refreshService, never()).resolve(eq(DashboardTrendMetric.PURCHASE), any(), any());
        verify(fixture.refreshService, never()).resolve(eq(DashboardTrendMetric.PURCHASE_RETURN), any(), any());
    }

    private static class Fixture {
        private final DashboardPermissionGuard permissionGuard = mock(DashboardPermissionGuard.class);
        private final DashboardTrendDailyAmountRefreshService refreshService = mock(DashboardTrendDailyAmountRefreshService.class);
        private final LoginUser user = new LoginUser();

        private Fixture() {
            when(permissionGuard.canViewSales(user)).thenReturn(true);
            when(permissionGuard.canViewPurchase(user)).thenReturn(true);
        }

        private void stubAmounts(long sales, long purchase, long salesReturn, long purchaseReturn) {
            LocalDate today = LocalDate.now();
            when(refreshService.resolve(eq(DashboardTrendMetric.SALES), any(), any())).thenReturn(Map.of(today, sales));
            when(refreshService.resolve(eq(DashboardTrendMetric.PURCHASE), any(), any())).thenReturn(Map.of(today, purchase));
            when(refreshService.resolve(eq(DashboardTrendMetric.SALES_RETURN), any(), any())).thenReturn(Map.of(today, salesReturn));
            when(refreshService.resolve(eq(DashboardTrendMetric.PURCHASE_RETURN), any(), any())).thenReturn(Map.of(today, purchaseReturn));
        }

        private DashboardTrendPointVO loadToday() {
            return new DashboardTrendLoader(permissionGuard, refreshService).load(user).getLast();
        }
    }
}