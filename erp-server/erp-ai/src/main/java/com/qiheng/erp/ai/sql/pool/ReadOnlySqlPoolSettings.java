package com.qiheng.erp.ai.sql.pool;

/**
 * 只读连接池的配置。
 *
 * <p>从 {@code erp.ai.sql.datasource.*} 与 {@code erp.ai.sql.session-read-only} 收敛而来，
 * 让池的创建不依赖 Spring 的 {@code @Value} 注解，便于测试与替换。</p>
 *
 * @param url             JDBC URL（必须带 {@code allowMultiQueries=false}）
 * @param username        只读账号
 * @param password        口令
 * @param driverClassName 驱动
 * @param poolName        池名（监控用）
 * @param initialSize     初始连接数
 * @param minIdle         最小空闲
 * @param maxActive       最大连接数
 * @param maxWaitMs       拿不到连接的最长等待
 * @param validationSql   连接校验语句
 * @param slowSqlMillis   慢 SQL 阈值（毫秒）
 * @param sessionReadOnly 是否在连接建立时设置会话只读
 * @author Li
 * @since 2026-10-26
 */
public record ReadOnlySqlPoolSettings(
        String url,
        String username,
        String password,
        String driverClassName,
        String poolName,
        int initialSize,
        int minIdle,
        int maxActive,
        long maxWaitMs,
        String validationSql,
        long slowSqlMillis,
        boolean sessionReadOnly) {
}