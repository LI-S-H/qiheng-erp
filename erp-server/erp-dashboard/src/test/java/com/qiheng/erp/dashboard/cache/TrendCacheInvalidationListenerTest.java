package com.qiheng.erp.dashboard.cache;

import com.qiheng.erp.common.event.dashboard.DashboardTrendInvalidatedEvent;
import com.qiheng.erp.common.event.dashboard.DashboardTrendMetric;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TrendCacheInvalidationListenerTest {

    @Test
    void shouldInvalidateOnlyTheChangedMetricAndDateAfterEvent() {
        TrendDailyAmountRefreshService refreshService = mock(TrendDailyAmountRefreshService.class);
        TrendCacheInvalidationListener listener = new TrendCacheInvalidationListener(refreshService);
        LocalDate businessDate = LocalDate.of(2026, 9, 1);

        listener.invalidate(new DashboardTrendInvalidatedEvent(DashboardTrendMetric.SALES_RETURN, businessDate));

        verify(refreshService).invalidate(DashboardTrendMetric.SALES_RETURN, businessDate);
    }
}