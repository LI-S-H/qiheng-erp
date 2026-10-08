package com.qiheng.erp.dashboard.exception;

import com.qiheng.erp.common.event.dashboard.DashboardTrendMetric;

import java.io.Serial;
import java.time.LocalDate;

/**
 * 趋势缓存失效重试耗尽后的上报包装异常。
 *
 * <p>异常简单名 {@code TrendCacheInvalidateFailedException} 作为
 * {@code system_exception.error_code} 写入，用于工作台精准定位"趋势缓存失效失败"，
 * 与 HTTP 业务异常、定时任务失败区分开。</p>
 *
 * @author Li
 * @since 2026-10-08
 */
public class TrendCacheInvalidateFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TrendCacheInvalidateFailedException(DashboardTrendMetric metric,
                                               LocalDate businessDate,
                                               Throwable cause) {
        super("趋势缓存失效失败 metric=" + metric + " businessDate=" + businessDate, cause);
    }
}
