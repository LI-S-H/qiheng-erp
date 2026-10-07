package com.qiheng.erp.dashboard.job;

import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.dashboard.cache.TrendDailyAmountRefreshService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DashboardTrendDailyCacheJobTest {

    @Test
    void shouldFinalizeYesterday() {
        TrendDailyAmountRefreshService refreshService = mock(TrendDailyAmountRefreshService.class);
        DashboardTrendDailyCacheJob job = new DashboardTrendDailyCacheJob(refreshService,
                mock(SystemExceptionMqPublisher.class));
        job.finalizeYesterday();

        verify(refreshService).refreshDay(LocalDate.now().minusDays(1));
    }

    @Test
    void shouldReportJobFailureWhenRefreshThrows() {
        TrendDailyAmountRefreshService refreshService = mock(TrendDailyAmountRefreshService.class);
        SystemExceptionMqPublisher publisher = mock(SystemExceptionMqPublisher.class);
        org.mockito.Mockito.doThrow(new RuntimeException("DB 不可用"))
                .when(refreshService).refreshDay(LocalDate.now().minusDays(1));
        DashboardTrendDailyCacheJob job = new DashboardTrendDailyCacheJob(refreshService, publisher);

        // 校准失败不能向外抛（调度线程吞掉），且必须上报 JOB_FAILED 异常消息
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(job::finalizeYesterday);
        verify(publisher).publishJobFailure(
                org.mockito.ArgumentMatchers.eq("dashboard-trend-daily-finalize"),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("HIGH"));
    }

    @Test
    void shouldNotReportWhenRefreshSucceeds() {
        TrendDailyAmountRefreshService refreshService = mock(TrendDailyAmountRefreshService.class);
        SystemExceptionMqPublisher publisher = mock(SystemExceptionMqPublisher.class);
        DashboardTrendDailyCacheJob job = new DashboardTrendDailyCacheJob(refreshService, publisher);
        job.finalizeYesterday();
        verifyNoInteractions(publisher);
    }
}