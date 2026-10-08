package com.qiheng.erp.dashboard.cache;

import com.qiheng.erp.common.event.dashboard.DashboardTrendInvalidatedEvent;
import com.qiheng.erp.common.event.dashboard.DashboardTrendMetric;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.dashboard.exception.TrendCacheInvalidateFailedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;

/**
 * 趋势缓存失效监听器：业务事务提交后删除对应日期缓存。
 *
 * <p>Redis 抖动导致失效失败时执行线性重试，避免缓存与 DB 短暂漂移；
 * 重试耗尽后通过 {@link SystemExceptionMqPublisher} 上报系统异常让运维感知，
 * 异常不再向上抛出——{@code @TransactionalEventListener(AFTER_COMMIT)} 阶段的事务
 * 已提交，监听器异常原本也会被 Spring 默认 ErrorHandler 吞掉，仅留 ERROR 日志，
 * 改造后让"失败可观测"，下次趋势读未命中按需回填 SQL 仍能恢复一致。</p>
 *
 * @author Li
 * @since 2026-09-02
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrendCacheInvalidationListener {

    /** 与 DashboardDailySnapshotJob / DashboardMonthlySnapshotJob 对齐：3 次重试、1s/2s 递增 */
    private static final int MAX_RETRY = 3;
    private static final long BASE_DELAY_MS = 1000L;

    private final TrendDailyAmountRefreshService trendDailyAmountRefreshService;
    private final SystemExceptionMqPublisher systemExceptionMqPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void invalidate(DashboardTrendInvalidatedEvent event) {
        invalidateWithRetry(event.metric(), event.businessDate());
    }

    /**
     * 线性重试刷新缓存；耗尽后上报系统异常并吞掉异常（不让 AFTER_COMMIT 阶段影响业务响应）。
     *
     * <p>内部已用 Redisson 锁保护（{@link TrendDailyAmountRefreshService#invalidate}），
     * 重试整段调用等价于"重新抢锁 + 重新失效"；锁在 finally 中释放，下次重试不会冲突。</p>
     *
     * @param metric 趋势维度
     * @param businessDate 业务日期
     */
    private void invalidateWithRetry(DashboardTrendMetric metric, LocalDate businessDate) {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                trendDailyAmountRefreshService.invalidate(metric, businessDate);
                return;
            } catch (Exception ex) {
                last = ex;
                log.error("趋势缓存失效第{}/{}次失败 metric={} businessDate={}",
                        attempt, MAX_RETRY, metric, businessDate, ex);
                if (attempt < MAX_RETRY) {
                    try {
                        // 1s / 2s 递增延迟，与 DashboardDailySnapshotJob.retryStep 一致
                        Thread.sleep(BASE_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("趋势缓存失效重试等待被中断，停止重试 metric={} businessDate={}",
                                metric, businessDate);
                        break;
                    }
                }
            }
        }
        // 重试耗尽：上报系统异常，运维可在工作台看到并人工触发刷新；不向上抛
        systemExceptionMqPublisher.publishSystemError(
                new TrendCacheInvalidateFailedException(metric, businessDate, last));
    }
}
