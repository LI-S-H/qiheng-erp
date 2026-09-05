package com.qiheng.erp.dashboard.job;

import com.qiheng.erp.dashboard.cache.TrendDailyAmountRefreshService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class DashboardTrendDailyCacheJobTest {

    @Test
    void shouldFinalizeYesterday() {
        TrendDailyAmountRefreshService refreshService = mock(TrendDailyAmountRefreshService.class);
        DashboardTrendDailyCacheJob job = new DashboardTrendDailyCacheJob(refreshService);
        job.finalizeYesterday();

        verify(refreshService).refreshDay(LocalDate.now().minusDays(1));
    }
}