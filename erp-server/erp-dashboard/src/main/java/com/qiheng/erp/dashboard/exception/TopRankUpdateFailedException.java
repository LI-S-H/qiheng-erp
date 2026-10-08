package com.qiheng.erp.dashboard.exception;

import java.io.Serial;

/**
 * TOP 商品排行增量更新重试耗尽后的上报包装异常。
 *
 * <p>异常简单名 {@code TopRankUpdateFailedException} 作为
 * {@code system_exception.error_code} 写入，用于工作台精准定位"TOP 排行增量失败"，
 * 与 HTTP 业务异常、定时任务失败、趋势缓存失效区分开。</p>
 *
 * @author Li
 * @since 2026-10-08
 */
public class TopRankUpdateFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TopRankUpdateFailedException(int itemCount, int deltaSign, Throwable cause) {
        super("TOP 排行增量更新失败 items=" + itemCount + " deltaSign=" + deltaSign, cause);
    }
}
