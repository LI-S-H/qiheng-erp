package com.qiheng.erp.dashboard.cache;

import com.qiheng.erp.common.event.dashboard.DashboardTrendMetric;
import com.qiheng.erp.dashboard.service.support.DashboardTrendDailyAmountQuery;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardTrendDailyAmountRefreshServiceTest {

    @Test
    void shouldQueryOneContinuousRangeAndFillEveryMissingDate() throws InterruptedException {
        DashboardTrendDailyAmountCache cache = mock(DashboardTrendDailyAmountCache.class);
        DashboardTrendDailyAmountQuery query = mock(DashboardTrendDailyAmountQuery.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(any(String.class))).thenReturn(lock);
        when(redissonClient.getMultiLock(any(RLock[].class))).thenReturn(lock);
        when(lock.tryLock(any(Long.class), any())).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        LocalDate first = LocalDate.of(2026, 8, 1);
        LocalDate last = LocalDate.of(2026, 8, 3);
        List<LocalDate> allDates = first.datesUntil(last.plusDays(1)).toList();
        when(cache.get(eq(DashboardTrendMetric.SALES), any()))
                .thenReturn(new DashboardTrendDailyAmountCache.GetResult(Map.of(), allDates))
                .thenReturn(new DashboardTrendDailyAmountCache.GetResult(Map.of(), allDates));
        when(query.query(DashboardTrendMetric.SALES, first, last)).thenReturn(Map.of(first, 100L, last, 300L));
        DashboardTrendDailyAmountRefreshService service = new DashboardTrendDailyAmountRefreshService(cache, query, redissonClient);

        Map<LocalDate, Long> result = service.resolve(DashboardTrendMetric.SALES, first, last);

        assertThat(result).containsEntry(first, 100L)
                .containsEntry(first.plusDays(1), 0L)
                .containsEntry(last, 300L);
        verify(query).query(DashboardTrendMetric.SALES, first, last);
        verify(cache).put(DashboardTrendMetric.SALES,
                Map.of(first, 100L, first.plusDays(1), 0L, last, 300L));
        verify(lock).unlock();
    }

    @Test
    void shouldInvalidateOnlyAfterDailyLockIsAcquired() {
        DashboardTrendDailyAmountCache cache = mock(DashboardTrendDailyAmountCache.class);
        DashboardTrendDailyAmountQuery query = mock(DashboardTrendDailyAmountQuery.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(any(String.class))).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        LocalDate businessDate = LocalDate.of(2026, 9, 2);
        DashboardTrendDailyAmountRefreshService service = new DashboardTrendDailyAmountRefreshService(cache, query, redissonClient);

        service.invalidate(DashboardTrendMetric.SALES, businessDate);

        verify(lock).lock();
        verify(cache).invalidate(DashboardTrendMetric.SALES, businessDate);
        verify(lock).unlock();
    }

    @Test
    void shouldForceRefreshEveryMetricForFinalization() {
        DashboardTrendDailyAmountCache cache = mock(DashboardTrendDailyAmountCache.class);
        DashboardTrendDailyAmountQuery query = mock(DashboardTrendDailyAmountQuery.class);
        RedissonClient redissonClient = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redissonClient.getLock(any(String.class))).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        LocalDate businessDate = LocalDate.of(2026, 9, 2);
        for (DashboardTrendMetric metric : DashboardTrendMetric.values()) {
            when(query.query(metric, businessDate, businessDate)).thenReturn(Map.of(businessDate, (long) metric.ordinal()));
        }
        DashboardTrendDailyAmountRefreshService service = new DashboardTrendDailyAmountRefreshService(cache, query, redissonClient);

        service.refreshDay(businessDate);

        for (DashboardTrendMetric metric : DashboardTrendMetric.values()) {
            verify(query).query(metric, businessDate, businessDate);
            verify(cache).put(metric, Map.of(businessDate, (long) metric.ordinal()));
        }
    }
}