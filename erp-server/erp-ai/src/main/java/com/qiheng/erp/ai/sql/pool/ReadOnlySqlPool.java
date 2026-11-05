package com.qiheng.erp.ai.sql.pool;

import com.alibaba.druid.filter.Filter;
import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * AI 取数专用只读连接池。
 *
 * <p><b>它不是 Spring 容器里的 DataSource bean</b>：Spring Boot 的
 * {@code DataSourceAutoConfiguration.PooledDataSourceConfiguration} 上挂着
 * {@code @ConditionalOnMissingBean({DataSource.class, XADataSource.class})}——
 * 容器里一旦出现第二个 DataSource，自动装配就不再创建主数据源，MyBatis-Plus 随即启动失败。
 * 所以这个池由 {@code ReadOnlySqlExecutor} 私有持有，容器看不见它。</p>
 *
 * <p>两道过滤器：{@code wall} 拦通用危险形态（写语句、多语句、{@code SELECT *}、注释、
 * {@code OUTFILE}），{@code stat} 记慢 SQL。表/列白名单不在这里——那需要 ERP 的权限模型，
 * 由 SQL 守卫负责。</p>
 *
 * @author Li
 * @since 2026-10-26
 */
public class ReadOnlySqlPool implements AutoCloseable {

    /** 会话级只读：账号本身只读时这行是冗余保险，账号不可用时靠它兜底。 */
    private static final String SESSION_READ_ONLY_SQL = "SET SESSION TRANSACTION READ ONLY";

    /** 过滤器名：wall 是 SQL 防火墙，stat 是慢 SQL 与执行统计。 */
    private static final String FILTERS = "wall,stat";

    private final DruidDataSource pool;

    public ReadOnlySqlPool(ReadOnlySqlPoolSettings settings) {
        this.pool = build(settings);
    }

    /**
     * 取一条只读连接；调用方负责关闭。
     *
     * @return 连接
     * @throws SQLException 取连接失败
     */
    public Connection open() throws SQLException {
        return pool.getConnection();
    }

    /**
     * 连接池本体：只给同包测试用。
     *
     * @return Druid 连接池
     */
    public DruidDataSource unwrap() {
        return pool;
    }

    @Override
    public void close() {
        pool.close();
    }

    /**
     * 建池。池是懒初始化的：真正的连接在第一次取连接时才建立，
     * 所以数据库暂时不可用不会让应用启动失败。
     */
    private static DruidDataSource build(ReadOnlySqlPoolSettings settings) {
        DruidDataSource dataSource = new DruidDataSource();
        dataSource.setUrl(settings.url());
        dataSource.setUsername(settings.username());
        dataSource.setPassword(settings.password());
        dataSource.setDriverClassName(settings.driverClassName());
        dataSource.setName(settings.poolName());
        dataSource.setInitialSize(settings.initialSize());
        dataSource.setMinIdle(settings.minIdle());
        dataSource.setMaxActive(settings.maxActive());
        dataSource.setMaxWait(settings.maxWaitMs());
        dataSource.setValidationQuery(settings.validationSql());
        dataSource.setTestWhileIdle(true);
        dataSource.setTestOnBorrow(false);
        dataSource.setTimeBetweenEvictionRunsMillis(60_000L);
        dataSource.setMinEvictableIdleTimeMillis(300_000L);
        if (settings.sessionReadOnly()) {
            dataSource.setConnectionInitSqls(List.of(SESSION_READ_ONLY_SQL));
        }
        applyFilters(dataSource, settings.slowSqlMillis());
        return dataSource;
    }

    /**
     * 装配过滤器：wall 拦危险语句形态，stat 记慢 SQL。
     *
     * <p>过滤器由 Druid 自己创建（{@code setFilters}），这里只拿到实例并改配置，
     * 不自己 new，避免 provider 与 dbType 的装配顺序踩坑。</p>
     */
    private static void applyFilters(DruidDataSource dataSource, long slowSqlMillis) {
        try {
            dataSource.setFilters(FILTERS);
        } catch (SQLException exception) {
            throw new IllegalStateException("AI 取数连接池过滤器装配失败", exception);
        }
        for (Filter filter : dataSource.getProxyFilters()) {
            if (filter instanceof WallFilter wall) {
                wall.setDbType("mysql");
                wall.setThrowException(true);
                wall.setLogViolation(true);
                wall.setConfig(wallConfig());
            } else if (filter instanceof StatFilter stat) {
                stat.setSlowSqlMillis(slowSqlMillis);
                stat.setLogSlowSql(true);
            }
        }
    }

    /**
     * SQL 防火墙规则：只放行只读查询，常见危险形态一律拦下。
     */
    private static WallConfig wallConfig() {
        WallConfig config = new WallConfig();
        config.setSelectAllColumnAllow(false);          // 禁 SELECT *
        config.setCommentAllow(false);                  // 禁注释
        config.setMultiStatementAllow(false);           // 禁多语句
        config.setNoneBaseStatementAllow(false);
        config.setInsertAllow(false);
        config.setUpdateAllow(false);
        config.setDeleteAllow(false);
        config.setReplaceAllow(false);
        config.setMergeAllow(false);
        config.setCallAllow(false);
        config.setSetAllow(false);
        config.setTruncateAllow(false);
        config.setCreateTableAllow(false);
        config.setDropTableAllow(false);
        config.setAlterTableAllow(false);
        config.setRenameTableAllow(false);
        config.setSelectIntoAllow(false);
        config.setSelectIntoOutfileAllow(false);
        config.setSelectWhereAlwayTrueCheck(true);      // 拦 WHERE 1=1
        config.setSelectHavingAlwayTrueCheck(true);
        config.setConditionAndAlwayTrueAllow(false);
        return config;
    }
}