package com.qiheng.erp.dashboard.cache;

import com.qiheng.erp.common.event.dashboard.DashboardTrendMetric;
import com.qiheng.erp.dashboard.service.support.DashboardTrendDailyAmountQuery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 协调经营趋势的按日缓存回填与失效。
 *
 * <p>缺失日期会先按“指标 + 日期”取得同一把 Redisson 锁，再以一个连续日期范围查询数据库。
 * 因此业务提交后的按日失效不会被并发回填的旧结果覆盖。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrendDailyAmountRefreshService {

    private static final String LOCK_KEY_PREFIX = "dashboard:trend:daily-amount:lock:";
    private static final long LOCK_WAIT_SECONDS = 2L;

    private final TrendDailyAmountCache trendDailyAmountCache;
    private final DashboardTrendDailyAmountQuery dailyAmountQuery;
    private final RedissonClient redissonClient;

    /** 批量读取日期范围；仅在 miss 时查询数据库并回填缺失字段。 */
    public Map<LocalDate, Long> resolve(DashboardTrendMetric metric, LocalDate startDate, LocalDate endDate) {
        // 1. 获取一个左闭右开的日期范围, 包含 startDate 但不包含 endDate
        List<LocalDate> dates = startDate.datesUntil(endDate.plusDays(1)).toList();
        // 2. 从缓存中获取所有日期的对应数据
        var cached = trendDailyAmountCache.get(metric, dates);
        Map<LocalDate, Long> result = new HashMap<>(cached.hits());
        if (cached.misses().isEmpty()) {
            return result;
        }
        // 3. 从数据库中查询缺失日期的对应数据并回填且更新redis缓存
        return refreshMissing(metric, cached.misses(), result);
    }

    /** 强制按数据库重算指定日期的全部趋势维度，供日终校准使用。 */
    public void refreshDay(LocalDate businessDate) {
        List<RLock> locks = new ArrayList<>();
        // 1. 获取四个维度的锁
        for (DashboardTrendMetric metric : DashboardTrendMetric.values()) {
            locks.add(lock(metric, List.of(businessDate)));
        }
        RLock multiLock = locks.size() == 1
                ? locks.getFirst()
                : redissonClient.getMultiLock(locks.toArray(RLock[]::new));
        multiLock.lock();
        try {
            // 2. 查询数据库，更新缓存
            for (DashboardTrendMetric metric : DashboardTrendMetric.values()) {
                long amount = dailyAmountQuery.query(metric, businessDate, businessDate)
                        .getOrDefault(businessDate, 0L);
                trendDailyAmountCache.put(metric, Map.of(businessDate, amount));
            }
        } finally {
            if (multiLock.isHeldByCurrentThread()) {
                multiLock.unlock();
            }
        }
    }

    /** 在维度锁内删除字段，避免并发时写回旧值。 */
    public void invalidate(DashboardTrendMetric metric, LocalDate businessDate) {
        if (businessDate == null) {
            return;
        }
        RLock lock = lock(metric, List.of(businessDate));
        lock.lock();
        try {
            trendDailyAmountCache.invalidate(metric, businessDate);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 从数据库中查询缺失日期的对应数据并回填且更新redis缓存。 */
    private Map<LocalDate, Long> refreshMissing(DashboardTrendMetric metric,
                                                 List<LocalDate> missingDates,
                                                 Map<LocalDate, Long> result) {
        RLock lock = lock(metric, missingDates);
        boolean locked = false;
        try {
            // 1. 尝试获取锁，超时时间为 2_WAIT_SECONDS 秒
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("经营趋势缓存回填未获取锁，先尝试读取缓存 metric={} dates={}", metric, missingDates);
                // 1. 二次查询缓存，避免并发时写回旧值
                var afterWait = trendDailyAmountCache.get(metric, missingDates);
                result.putAll(afterWait.hits());
                // 2. 如果所有日期都命中缓存，直接返回结果
                if (afterWait.misses().isEmpty()) {
                    return result;
                }
                // 3. 合并查询结果并返回
                return mergeQueryResultAndRefresh(metric, afterWait.misses(), result, false);
            }
            // 2. 二次查询缓存，避免并发时写回旧值
            var afterWaiting = trendDailyAmountCache.get(metric, missingDates);
            result.putAll(afterWaiting.hits());
            // 3. 如果所有日期都命中缓存，直接返回结果
            if (afterWaiting.misses().isEmpty()) {
                return result;
            }
            // 4. 合并查询结果并更新redis缓存
            return mergeQueryResultAndRefresh(metric, afterWaiting.misses(), result, true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("经营趋势缓存回填等待锁被中断，改为仅查询不写缓存 metric={} dates={}", metric, missingDates);
            return mergeQueryResultAndRefresh(metric, missingDates, result, false);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 合并查询结果，回填缺失日期的对应数据并更新redis缓存。 */
    private Map<LocalDate, Long> mergeQueryResultAndRefresh(DashboardTrendMetric metric,
                                                            List<LocalDate> missingDates,
                                                            Map<LocalDate, Long> result,
                                                            boolean writeCache) {
        LocalDate rangeStart = missingDates.getFirst();
        LocalDate rangeEnd = missingDates.getLast();
        // 1. 从数据库中查询缺失日期的对应数据
        Map<LocalDate, Long> queriedAmounts = dailyAmountQuery.query(metric, rangeStart, rangeEnd);
        Map<LocalDate, Long> valuesToCache = new HashMap<>();
        // 2. 合并查询结果，回填缺失日期的对应数据
        for (LocalDate date : missingDates) {
            long amount = queriedAmounts.getOrDefault(date, 0L);
            result.put(date, amount);
            valuesToCache.put(date, amount);
        }
        // 3. 如果需要更新缓redis
        if (writeCache) {
            trendDailyAmountCache.put(metric, valuesToCache);
        }
        return result;
    }

    /** 获取指定趋势维度和日期范围的锁。 */
    private RLock lock(DashboardTrendMetric metric, List<LocalDate> dates) {
        List<RLock> locks = new ArrayList<>(dates.size());
        for (LocalDate date : dates) {
            locks.add(redissonClient.getLock(LOCK_KEY_PREFIX + metric.name().toLowerCase() + ':' + date));
        }
        return locks.size() == 1 ? locks.getFirst() : redissonClient.getMultiLock(locks.toArray(RLock[]::new));
    }
}