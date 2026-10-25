package com.qiheng.erp.ai.service.data.sql.pool;

import com.alibaba.druid.filter.stat.StatFilter;
import com.alibaba.druid.pool.DruidDataSource;
import com.alibaba.druid.wall.WallConfig;
import com.alibaba.druid.wall.WallFilter;
import org.junit.jupiter.api.Test;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 只读连接池的装配契约：不需要数据库就能验的部分。
 *
 * <p>连得上连不上由真库集成测试负责；这里只钉住"池被配成什么样"——
 * 这些参数一旦被改错，防护会静默失效，事后很难查。</p>
 *
 * @author Li
 * @since 2026-10-26
 */
class ReadOnlySqlPoolTest {

    private static final String URL = "jdbc:mysql://127.0.0.1:13307/erp?allowMultiQueries=false";

    private ReadOnlySqlPool pool() {
        return new ReadOnlySqlPool(new ReadOnlySqlPoolSettings(URL, "erp_ai_ro", "pwd",
                "com.mysql.cj.jdbc.Driver", "erp-ai-readonly-test", 1, 1, 2, 3000L,
                "SELECT 1", 1000L, true));
    }

    @Test
    void poolIsNamedAndSizedForAiQueries() {
        DruidDataSource dataSource = pool().unwrap();

        assertThat(dataSource.getName()).isEqualTo("erp-ai-readonly-test");
        assertThat(dataSource.getMaxActive()).isEqualTo(2);
        assertThat(dataSource.getMaxWait()).isEqualTo(3000L);
        assertThat(dataSource.getUrl()).isEqualTo(URL);
        assertThat(dataSource.getUsername()).isEqualTo("erp_ai_ro");
    }

    @Test
    void sessionIsForcedReadOnly() {
        Collection<String> initSqls = pool().unwrap().getConnectionInitSqls();

        assertThat(initSqls).containsExactly("SET SESSION TRANSACTION READ ONLY");
    }

    @Test
    void wallFilterBlocksDangerousStatementShapes() {
        WallConfig config = wallConfig();

        assertThat(config.isSelectAllColumnAllow()).isFalse();
        assertThat(config.isCommentAllow()).isFalse();
        assertThat(config.isMultiStatementAllow()).isFalse();
        assertThat(config.isNoneBaseStatementAllow()).isFalse();
        assertThat(config.isInsertAllow()).isFalse();
        assertThat(config.isUpdateAllow()).isFalse();
        assertThat(config.isDeleteAllow()).isFalse();
        assertThat(config.isReplaceAllow()).isFalse();
        assertThat(config.isMergeAllow()).isFalse();
        assertThat(config.isCallAllow()).isFalse();
        assertThat(config.isSetAllow()).isFalse();
        assertThat(config.isTruncateAllow()).isFalse();
        assertThat(config.isCreateTableAllow()).isFalse();
        assertThat(config.isDropTableAllow()).isFalse();
        assertThat(config.isAlterTableAllow()).isFalse();
        assertThat(config.isRenameTableAllow()).isFalse();
        assertThat(config.isSelectIntoAllow()).isFalse();
        assertThat(config.isSelectIntoOutfileAllow()).isFalse();
        assertThat(config.isSelectWhereAlwayTrueCheck()).isTrue();
    }

    @Test
    void statFilterRecordsSlowSql() {
        StatFilter stat = statFilter();

        assertThat(stat.getSlowSqlMillis()).isEqualTo(1000L);
        assertThat(stat.isLogSlowSql()).isTrue();
    }

    private WallConfig wallConfig() {
        WallFilter wall = (WallFilter) pool().unwrap().getProxyFilters().stream()
                .filter(WallFilter.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("wall 过滤器未装配"));
        return wall.getConfig();
    }

    private StatFilter statFilter() {
        return (StatFilter) pool().unwrap().getProxyFilters().stream()
                .filter(StatFilter.class::isInstance)
                .findFirst()
                .orElseThrow(() -> new AssertionError("stat 过滤器未装配"));
    }
}