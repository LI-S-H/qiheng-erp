package com.qiheng.erp.dashboard.cache;

import com.qiheng.erp.common.event.dashboard.DashboardTrendInvalidatedEvent;
import com.qiheng.erp.common.event.dashboard.DashboardTrendMetric;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.dashboard.exception.TrendCacheInvalidateFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 验证趋势缓存失效监听器的线性重试与失败上报：
 * <ul>
 *   <li>第一次成功 → 调用一次，不上报系统异常</li>
 *   <li>中间成功（重试耗尽前恢复） → 调用 N 次，不上报系统异常</li>
 *   <li>三次都失败 → 调用三次，上报一次 TrendCacheInvalidateFailedException，且不向上抛</li>
 * </ul>
 *
 * @author Li
 * @since 2026-10-08
 */
class TrendCacheInvalidationListenerTest {

    private TrendDailyAmountRefreshService refreshService;
    private SystemExceptionMqPublisher publisher;
    private TrendCacheInvalidationListener listener;

    @BeforeEach
    void setUp() {
        refreshService = mock(TrendDailyAmountRefreshService.class);
        publisher = mock(SystemExceptionMqPublisher.class);
        listener = new TrendCacheInvalidationListener(refreshService, publisher);
    }

    @Test
    void invalidatesOnceAndDoesNotReportWhenFirstAttemptSucceeds() {
        DashboardTrendInvalidatedEvent event = new DashboardTrendInvalidatedEvent(
                DashboardTrendMetric.SALES, LocalDate.of(2026, 10, 8));

        listener.invalidate(event);

        verify(refreshService, times(1))
                .invalidate(DashboardTrendMetric.SALES, LocalDate.of(2026, 10, 8));
        verify(publisher, never()).publishSystemError(any());
    }

    @Test
    void retriesAndRecoversBeforeExhaustion() {
        DashboardTrendInvalidatedEvent event = new DashboardTrendInvalidatedEvent(
                DashboardTrendMetric.PURCHASE, LocalDate.of(2026, 10, 8));
        // 前两次失败，第三次成功
        doThrow(new RuntimeException("Redis 抖动 1"))
                .doThrow(new RuntimeException("Redis 抖动 2"))
                .doNothing()
                .when(refreshService)
                .invalidate(DashboardTrendMetric.PURCHASE, LocalDate.of(2026, 10, 8));

        listener.invalidate(event);

        verify(refreshService, times(3))
                .invalidate(DashboardTrendMetric.PURCHASE, LocalDate.of(2026, 10, 8));
        verify(publisher, never()).publishSystemError(any());
    }

    @Test
    void reportsSystemErrorAndSwallowsAfterAllAttemptsFail() {
        DashboardTrendInvalidatedEvent event = new DashboardTrendInvalidatedEvent(
                DashboardTrendMetric.SALES, LocalDate.of(2026, 10, 8));
        doThrow(new RuntimeException("Redis 持续不可用"))
                .when(refreshService)
                .invalidate(any(), any());

        // 监听器不能向上抛异常——AFTER_COMMIT 阶段业务响应已发出
        assertThatCode(() -> listener.invalidate(event)).doesNotThrowAnyException();

        verify(refreshService, times(3))
                .invalidate(DashboardTrendMetric.SALES, LocalDate.of(2026, 10, 8));
        ArgumentCaptor<RuntimeException> captor = ArgumentCaptor.forClass(RuntimeException.class);
        verify(publisher, times(1)).publishSystemError(captor.capture());
        // errorCode=TrendCacheInvalidateFailedException 类简单名,运维可在工作台精准定位
        assertThat(captor.getValue()).isInstanceOf(TrendCacheInvalidateFailedException.class);
        assertThat(captor.getValue().getCause()).hasMessage("Redis 持续不可用");
    }

    @Test
    void doesNothingWhenBusinessDateIsNull() {
        DashboardTrendInvalidatedEvent event = new DashboardTrendInvalidatedEvent(
                DashboardTrendMetric.SALES, null);

        listener.invalidate(event);

        // 下层 invalidate(null) 会早返回；监听器不应额外处理或上报
        verify(refreshService, times(1))
                .invalidate(DashboardTrendMetric.SALES, null);
        verifyNoInteractions(publisher);
    }
}
