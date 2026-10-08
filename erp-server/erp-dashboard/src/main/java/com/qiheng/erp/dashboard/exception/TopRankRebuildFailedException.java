package com.qiheng.erp.dashboard.exception;

import java.io.Serial;

/**
 * TOP 排行重建失败上报包装异常。
 *
 * <p>由 {@code DashboardTopProductLoader.triggerRebuild} 在 rebuild 失败时
 * (FAILED 路径或 RuntimeException 穿透)上报,类简单名作为
 * {@code system_exception.error_code} 写入,用于工作台精准定位"重建失败"。</p>
 *
 * <p>与 {@link TopRankUpdateFailedException} 的区别:
 * <ul>
 *   <li>TopRankUpdateFailedException:增量 onAdjust 重试耗尽(链路:HTTP 业务事件)</li>
 *   <li>TopRankRebuildFailedException:rebuild 整体失败(链路:脏数据/冷启动触发)</li>
 * </ul>
 *
 * @author Li
 * @since 2026-10-08
 */
public class TopRankRebuildFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TopRankRebuildFailedException(String reason, Throwable cause) {
        super("TOP 排行重建失败 " + reason, cause);
    }
}
