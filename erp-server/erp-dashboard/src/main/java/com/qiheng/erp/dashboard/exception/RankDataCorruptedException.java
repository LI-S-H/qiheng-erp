package com.qiheng.erp.dashboard.exception;

import java.io.Serial;

/**
 * TOP 商品排行 Redis 脏数据异常。
 *
 * <p>readTop 读取到无法解析的 member/score/qty（理论上不会发生——所有字段都从强类型写入路径来，
 * 唯一污染路径是外部运维命令直接改 Redis）时抛出，由 {@code DashboardTopProductLoader}
 * 捕获后上报系统异常 + 触发防雪崩重建。</p>
 *
 * <p>异常简单名 {@code RankDataCorruptedException} 作为
 * {@code SystemExceptionMqPublisher.publishSystemError} 传递的 cause，
 * 由 publisher 内部取 {@code e.getClass().getSimpleName()} 作为
 * {@code system_exception.error_code} 写入，用于工作台精准定位"TOP 排行数据被外部污染"。</p>
 *
 * <p>注意：本异常不抛到 Controller 层（不会被 GlobalExceptionHandler 转为 HTTP 响应），
 * 由编排层（Loader）内部消费，确保业务响应只返回空数据 + 工作台可见异常记录。</p>
 *
 * @author Li
 * @since 2026-10-08
 */
public class RankDataCorruptedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public RankDataCorruptedException(String member, String score, String qty) {
        super("TOP 排行 Redis 脏数据 member=" + member + " score=" + score + " qty=" + qty);
    }
}
