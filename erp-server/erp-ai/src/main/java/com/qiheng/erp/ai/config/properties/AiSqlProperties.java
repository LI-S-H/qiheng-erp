package com.qiheng.erp.ai.config.properties;

import com.qiheng.erp.ai.sql.pool.ReadOnlySqlPoolSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * AI 取数底座的配置。
 *
 * <p>原来散在执行器构造器上的 16 个 {@code @Value} 收敛到这里。属性名与
 * {@code application-ai.yml} 完全一致，只跟着前缀 {@code erp.ai.sql} 绑定，
 * 键名从 kebab-case 自动映射到驼峰（如 {@code max-wait-ms} → {@code maxWaitMs}）。</p>
 *
 * <p>本类只放值，不做业务判断；建池、校验上限仍由调用方负责。</p>
 *
 * @param datasource           只读连接池参数
 * @param sessionReadOnly      是否在连接建立时设置会话只读
 * @param pageSize             每页行数，同时也是"没写 LIMIT 时要不要让模型补声明"的阈值
 * @param maxTotalRows         单个证据项允许的最大行数
 * @param maxPages             最多翻几页
 * @param queryTimeoutSeconds  单条查询超时（秒）
 * @author Li
 * @since 2026-10-26
 */
@ConfigurationProperties(prefix = "erp.ai.sql")
public record AiSqlProperties(
        Datasource datasource,
        @DefaultValue("true") boolean sessionReadOnly,
        @DefaultValue("200") int pageSize,
        @DefaultValue("2000") int maxTotalRows,
        @DefaultValue("10") int maxPages,
        @DefaultValue("10") int queryTimeoutSeconds) {

    /**
     * 转成连接池要的纯值对象。
     *
     * <p>取数底座关闭时 {@code datasource} 允许为空（属性 Bean 仍然会绑定，
     * 只是没人调用本方法）；启用却没配连接信息时，用明确的错误信息失败，
     * 不要留到 Druid 里报一句看不懂的异常。</p>
     */
    public ReadOnlySqlPoolSettings toSettings() {
        if (datasource == null) {
            throw new IllegalStateException(
                    "erp.ai.sql.datasource 未配置：取数底座已启用，但没有连接信息");
        }
        return datasource.toSettings(sessionReadOnly);
    }

    /**
     * 只读连接池参数，对应 {@code erp.ai.sql.datasource.*}。
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
     */
    public record Datasource(
            String url,
            String username,
            String password,
            @DefaultValue("com.mysql.cj.jdbc.Driver") String driverClassName,
            @DefaultValue("erp-ai-readonly") String poolName,
            @DefaultValue("1") int initialSize,
            @DefaultValue("1") int minIdle,
            @DefaultValue("2") int maxActive,
            @DefaultValue("3000") long maxWaitMs,
            @DefaultValue("SELECT 1") String validationSql,
            @DefaultValue("1000") long slowSqlMillis) {

        ReadOnlySqlPoolSettings toSettings(boolean sessionReadOnly) {
            return new ReadOnlySqlPoolSettings(
                    nullToEmpty(url), nullToEmpty(username), nullToEmpty(password),
                    nullToEmpty(driverClassName), nullToEmpty(poolName),
                    initialSize, minIdle, maxActive, maxWaitMs,
                    nullToEmpty(validationSql), slowSqlMillis, sessionReadOnly);
        }

        /**
         * 绑定层把空值（如 {@code password: ${ERP_AI_SQL_PASSWORD:}}）绑成 null，
         * 原来 {@code @Value} 拿到的是空串。保持空串，别去赌 Druid 对 null 的处理。
         */
        private static String nullToEmpty(String value) {
            return value == null ? "" : value;
        }
    }
}
