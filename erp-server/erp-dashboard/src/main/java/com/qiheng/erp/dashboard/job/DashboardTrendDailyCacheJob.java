package com.qiheng.erp.dashboard.job;

import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.dashboard.cache.TrendDailyAmountRefreshService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 经营趋势按日缓存日终校准任务。
 *
 * <p>每天 00:05 按数据库强制重算昨日全部趋势维度。无人访问时也会写入 0，
 * 且可校准临近午夜的最后业务变更。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DashboardTrendDailyCacheJob {

    private static final String FINALIZE_LOCK_KEY = "'dashboard:job:trend-daily-finalize'";

    private final TrendDailyAmountRefreshService trendDailyAmountRefreshService;


    /** 日终校准昨天数据，将临近午夜的最后业务变更纳入最终值。 */
    @Scheduled(cron = "0 5 0 * * ?")
    @DistributedLock(key = FINALIZE_LOCK_KEY, leaseTime = 120)
    public void finalizeYesterday() {
        refresh(LocalDate.now().minusDays(1), "日终校准");
    }

    /** 刷新指定日期的经营趋势数据。 */
    private void refresh(LocalDate businessDate, String phase) {
        try {
            trendDailyAmountRefreshService.refreshDay(businessDate);
            log.info("工作台经营趋势{}完成 date={}", phase, businessDate);
        } catch (Exception exception) {
            // 失败时不把查询异常误写成 0；后续请求遇到缺失日期时会按需回填。
            log.error("工作台经营趋势{}失败 date={}", phase, businessDate, exception);
        }
    }
}