package com.qiheng.erp.ai.sql.execution;

import com.qiheng.erp.ai.sql.guard.model.GuardedSql;
import com.qiheng.erp.ai.sql.execution.model.ExecutionOutcome;
import com.qiheng.erp.ai.sql.execution.model.QueryResult;
import com.qiheng.erp.ai.sql.execution.model.SqlExecutionLimits;
import com.qiheng.erp.ai.sql.execution.model.SqlExecutionRequest;
import com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn;
import com.qiheng.erp.ai.sql.pool.ReadOnlySqlPool;
import com.qiheng.erp.ai.sql.pool.ReadOnlySqlPoolSettings;
import com.alibaba.druid.pool.DruidDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 取数连接池真库集成测试。
 *
 * <p>显式使用 {@code -Dai.mysql.it=true} 启用；连接参数可用系统属性覆盖：
 * {@code ai.sql.url}、{@code ai.sql.username}、{@code ai.sql.password}。</p>
 *
 * <p>验的是三件在单元测试里验不了的事：只读账号真的连得上、墙过滤器真的拦得住
 * 危险语句形态、主业务数据源没有被取数池顶掉。</p>
 *
 * @author Li
 * @since 2026-10-25
 */
@SpringJUnitConfig(ReadOnlySqlExecutorIT.TestConfig.class)
@EnabledIfSystemProperty(named = "ai.mysql.it", matches = "true")
class ReadOnlySqlExecutorIT {

    private static final String URL = System.getProperty("ai.sql.url",
            "jdbc:mysql://localhost:13307/erp?useUnicode=true&characterEncoding=utf8&useSSL=false"
                    + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&allowMultiQueries=false");

    private static final String USERNAME = System.getProperty("ai.sql.username", "erp_ai_ro");

    private static final String PASSWORD = System.getProperty("ai.sql.password", "erp_ai_ro_2026");

    @Autowired
    private ReadOnlySqlExecutor executor;

    @Test
    void connectsWithReadOnlyAccount() throws SQLException {
        ReadOnlySqlExecutor.ConnectionInfo info = executor.probe();

        assertThat(info.user()).contains(USERNAME);
        assertThat(info.database()).isEqualTo("erp");
        assertThat(info.serverVersion()).isNotBlank();
    }

    @Test
    void normalSelectIsExecuted() throws SQLException {
        try (Connection connection = executor.openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT 1")) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isEqualTo(1);
        }
    }

    @Test
    void writeStatementIsRejected() {
        assertThatThrownBy(() -> execute("UPDATE purchase_order SET remark = 'ai' WHERE id = -1"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void selectStarIsRejectedByWall() {
        // 这条 SQL 服务端本来是允许的：被拒只能来自 wall 过滤器，
        // 说明"禁 SELECT *"这条规则真的挂在链路上，而不是只写在配置里。
        assertThatThrownBy(() -> execute("SELECT * FROM purchase_order"))
                .isInstanceOf(SQLException.class)
                .satisfies(error -> assertThat(error.getMessage().toLowerCase())
                        .containsAnyOf("wall", "deny", "not allow"));
    }

    @Test
    void multiStatementIsRejected() {
        assertThatThrownBy(() -> execute("SELECT 1; SELECT 2"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void commentStatementIsRejected() {
        assertThatThrownBy(() -> execute("SELECT 1 -- 注释"))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void aiPoolUsesAnotherAccountThanBusinessPool() throws SQLException {
        HikariDataSource business = new HikariDataSource();
        business.setJdbcUrl(URL);
        business.setUsername(System.getProperty("ai.sql.business.username", "root"));
        business.setPassword(System.getProperty("ai.sql.business.password", "123456"));
        business.setMaximumPoolSize(1);
        try {
            assertThat(currentUser(business)).isNotEqualTo(currentUser(executor.openConnection()));
        } finally {
            business.close();
        }
    }

    @Test
    void containerStillHasSingleDataSourceBean() {
        // 取数池不是容器里的 DataSource bean：容器里仍然只有主数据源一个，
        // 否则 Spring Boot 会停止自动装配主池，MyBatis-Plus 拿不到数据源。
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
                .withUserConfiguration(SqlExecutorConfiguration.class)
                .withPropertyValues(
                        "spring.datasource.url=" + URL,
                        "spring.datasource.username=root",
                        "spring.datasource.password=123456",
                        "erp.ai.enabled=true",
                        "erp.ai.sql.enabled=true",
                        "erp.ai.sql.datasource.url=" + URL,
                        "erp.ai.sql.datasource.username=" + USERNAME,
                        "erp.ai.sql.datasource.password=" + PASSWORD)
                .run(context -> {
                    assertThat(context).hasSingleBean(DataSource.class);
                    assertThat(context.getBean(DataSource.class)).isNotInstanceOf(DruidDataSource.class);
                });
    }

    @Test
    void pagesThroughFullDataInStableOrder() throws SQLException {
        String table = "ai_paging_it";
        createFixture(table);
        try {
            GuardedSql guardedSql = new GuardedSql(
                    "SELECT id, label FROM " + table + " ORDER BY amount DESC",
                    Set.of(table), null,
                    List.of(new GuardedSql.SortKey("amount", true)),
                    table, "id");

            ExecutionOutcome outcome = executor.execute(
                    new SqlExecutionRequest("it-run", "it-step", guardedSql, true));

            assertThat(outcome).isInstanceOf(ExecutionOutcome.Success.class);
            QueryResult result = ((ExecutionOutcome.Success) outcome).result();
            assertThat(result.rowCount()).isEqualTo(450);
            assertThat(result.completed()).isTrue();
            assertThat(result.completed()).isTrue();
            assertThat(result.pages()).isEqualTo(3);
            assertThat(result.columns()).extracting(SqlQueryColumn::name).containsExactly("id", "label");

            // label 编码成 "amount:id"，整段验证 (amount DESC, id ASC) 严格有序
            for (int i = 1; i < result.rows().size(); i++) {
                String[] previous = result.rows().get(i - 1).get(1).toString().split(":");
                String[] current = result.rows().get(i).get(1).toString().split(":");
                int previousAmount = Integer.parseInt(previous[0]);
                int currentAmount = Integer.parseInt(current[0]);
                long previousId = Long.parseLong(previous[1]);
                long currentId = Long.parseLong(current[1]);
                assertThat(previousAmount > currentAmount
                        || (previousAmount == currentAmount && previousId < currentId))
                        .as("第 %d 行与第 %d 行的顺序", i, i + 1)
                        .isTrue();
            }
        } finally {
            dropFixture(table);
        }
    }

    /** 真实 MySQL 分组结果超过单页时保留原 SQL 并读取全部分组。 */
    @Test
    void readsAllGroupsBeyondPageSizeWithoutCursorRewrite() throws SQLException {
        String table = "ai_paging_group_it";
        createFixture(table);
        try {
            String sql = "SELECT id, COUNT(*) AS total FROM " + table + " GROUP BY id";
            GuardedSql guardedSql = new GuardedSql(sql, Set.of(table), null, List.of(), null, null);
            ExecutionOutcome outcome = executor.execute(new SqlExecutionRequest("group-run", "group-step", guardedSql, true));
            assertThat(outcome).isInstanceOf(ExecutionOutcome.Success.class);
            QueryResult result = ((ExecutionOutcome.Success) outcome).result();
            assertThat(result.rowCount()).isEqualTo(450);
            assertThat(result.pages()).isEqualTo(1);
            assertThat(result.completed()).isTrue();
        } finally {
            dropFixture(table);
        }
    }

    /** JOIN 与分组保持原 SQL 执行，检查实际分组数量和完整性。 */
    @Test
    void readsJoinedGroupsWithoutCursorRewrite() throws SQLException {
        String table = "ai_paging_join_it";
        createFixture(table);
        try {
            String sql = "SELECT a.amount, COUNT(b.id) AS total FROM " + table + " a JOIN " + table
                    + " b ON a.id = b.id GROUP BY a.amount ORDER BY a.amount";
            var guarded = new GuardedSql(sql, Set.of(table), null, List.of(), null, null);
            var outcome = executor.execute(new SqlExecutionRequest("join-group-run", "step", guarded, true));
            assertThat(outcome).isInstanceOf(ExecutionOutcome.Success.class);
            var result = ((ExecutionOutcome.Success) outcome).result();
            assertThat(result.completed()).isTrue();
            assertThat(result.rowCount()).isEqualTo(10);
            assertThat(result.rows()).allSatisfy(row -> assertThat(((Number) row.get(1)).longValue()).isEqualTo(45));
        } finally {
            dropFixture(table);
        }
    }

    /** 验证真实 MySQL 下有界聚合和明细游标的 2000/2001 行边界。 */
    @Test
    void respectsFullResultBoundariesForGroupedAndCursorQueries() throws SQLException {
        String table = "ai_paging_boundary_it";
        try (Connection connection = DriverManager.getConnection(URL, rootUser(), rootPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, amount BIGINT NOT NULL)");
        }
        try {
            try (Connection connection = DriverManager.getConnection(URL, rootUser(), rootPassword());
                 PreparedStatement insert = connection.prepareStatement("INSERT INTO " + table + " VALUES (?, ?)") ) {
                for (int i = 1; i <= 2001; i++) {
                    insert.setLong(1, i);
                    insert.setLong(2, i);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            String group = "SELECT id, COUNT(*) AS total FROM " + table;
            GuardedSql exactGroups = new GuardedSql(group + " WHERE id <= 2000 GROUP BY id", Set.of(table), null, List.of(), null, null);
            GuardedSql overGroups = new GuardedSql(group + " GROUP BY id", Set.of(table), null, List.of(), null, null);
            var exact = executor.execute(new SqlExecutionRequest("exact-groups", "step", exactGroups, true));
            assertThat(exact).isInstanceOf(ExecutionOutcome.Success.class);
            assertThat(((ExecutionOutcome.Success) exact).result().rowCount()).isEqualTo(2000);
            assertThat(((ExecutionOutcome.Success) exact).result().truncationReason()).isNull();
            var overGroupResult = executor.execute(new SqlExecutionRequest("over-groups", "step", overGroups, true));
            assertThat(overGroupResult).isInstanceOf(ExecutionOutcome.Success.class);
            assertThat(((ExecutionOutcome.Success) overGroupResult).result().rowCount()).isEqualTo(2000);
            assertThat(((ExecutionOutcome.Success) overGroupResult).result().completed()).isFalse();
            assertThat(((ExecutionOutcome.Success) overGroupResult).result().truncationReason()).isEqualTo("ROW_LIMIT");

            String detail = "SELECT id FROM " + table;
            var exactCursor = new GuardedSql(detail + " WHERE id <= 2000 ORDER BY id", Set.of(table), null,
                    List.of(new GuardedSql.SortKey("id", false)), table, "id");
            var overCursor = new GuardedSql(detail + " ORDER BY id", Set.of(table), null,
                    List.of(new GuardedSql.SortKey("id", false)), table, "id");
            var cursor = executor.execute(new SqlExecutionRequest("exact-cursor", "step", exactCursor, true));
            assertThat(cursor).isInstanceOf(ExecutionOutcome.Success.class);
            assertThat(((ExecutionOutcome.Success) cursor).result().rowCount()).isEqualTo(2000);
            var overCursorResult = executor.execute(new SqlExecutionRequest("over-cursor", "step", overCursor, true));
            assertThat(overCursorResult).isInstanceOf(ExecutionOutcome.Success.class);
            assertThat(((ExecutionOutcome.Success) overCursorResult).result().rowCount()).isEqualTo(2000);
            assertThat(((ExecutionOutcome.Success) overCursorResult).result().completed()).isFalse();
            assertThat(((ExecutionOutcome.Success) overCursorResult).result().truncationReason()).isEqualTo("ROW_LIMIT");
            ReadOnlySqlExecutor pageLimited = executor("erp-ai-page-limit-it", new SqlExecutionLimits(200, 2000, 1, 10));
            try {
                var pageResult = pageLimited.execute(new SqlExecutionRequest("page-limit", "step", overCursor, true));
                assertThat(pageResult).isInstanceOf(ExecutionOutcome.Success.class);
                assertThat(((ExecutionOutcome.Success) pageResult).result().rowCount()).isEqualTo(200);
                assertThat(((ExecutionOutcome.Success) pageResult).result().truncationReason()).isEqualTo("PAGE_LIMIT");
            } finally {
                pageLimited.destroy();
            }
        } finally {
            dropFixture(table);
        }
    }

    /** 验证子查询内聚合时，外层明细按原 SQL 完整读取。 */
    @Test
    void readsOuterDetailsWithAggregateSubquery() throws SQLException {
        String table = "ai_paging_subquery_it";
        createFixture(table);
        try {
            String sql = "SELECT id FROM " + table + " WHERE amount = (SELECT MAX(amount) FROM " + table + ") ORDER BY id";
            var guarded = new GuardedSql(sql, Set.of(table), null, List.of(), null, null);
            var outcome = executor.execute(new SqlExecutionRequest("subquery-run", "step", guarded, true));
            assertThat(outcome).isInstanceOf(ExecutionOutcome.Success.class);
            QueryResult result = ((ExecutionOutcome.Success) outcome).result();
            assertThat(result.completed()).isTrue();
            assertThat(result.rowCount()).isEqualTo(45);
            assertThat(result.rows()).allSatisfy(row ->
                    assertThat(((Number) row.get(0)).longValue() % 10).isEqualTo(9));
        } finally {
            dropFixture(table);
        }
    }

    /** 验证带引号的中文别名在真实 JDBC 多页读取中能正确定位游标列。 */
    @Test
    void pagesThroughQuotedAliases() throws SQLException {
        String table = "ai_paging_alias_it";
        createFixture(table);
        try {
            GuardedSql guardedSql = new GuardedSql(
                    "SELECT id AS `商品编号`, label AS `标签` FROM " + table + " ORDER BY id",
                    Set.of(table), null, List.of(new GuardedSql.SortKey("id", false)), table, "id");
            ExecutionOutcome outcome = executor.execute(
                    new SqlExecutionRequest("alias-run", "alias-step", guardedSql, true));

            assertThat(outcome).isInstanceOf(ExecutionOutcome.Success.class);
            QueryResult result = ((ExecutionOutcome.Success) outcome).result();
            assertThat(result.rowCount()).isEqualTo(450);
            assertThat(result.pages()).isEqualTo(3);
            assertThat(result.completed()).isTrue();
            assertThat(result.columns()).extracting(SqlQueryColumn::label).containsExactly("商品编号", "标签");
            for (int i = 0; i < result.rows().size(); i++) {
                assertThat(((Number) result.rows().get(i).get(0)).longValue()).isEqualTo(i + 1L);
            }
        } finally {
            dropFixture(table);
        }
    }

    /** 验证实际 MySQL 中无 LIMIT 的分组查询按完整原始数据聚合，不使用明细游标。 */
    @Test
    void probesGroupedResultsWithoutDetailCursor() throws SQLException {
        String table = "ai_group_probe_it";
        createFixture(table);
        try {
            GuardedSql guardedSql = new GuardedSql(
                    "SELECT amount, COUNT(id) AS total FROM " + table + " GROUP BY amount ORDER BY amount",
                    Set.of(table), null, List.of(), null, null);
            ExecutionOutcome outcome = executor.execute(
                    new SqlExecutionRequest("group-run", "group-step", guardedSql, false));
            assertThat(outcome).isInstanceOf(ExecutionOutcome.Success.class);
            QueryResult result = ((ExecutionOutcome.Success) outcome).result();
            assertThat(result.completed()).isTrue();
            assertThat(result.rowCount()).isEqualTo(10);
            assertThat(result.rows()).allSatisfy(row ->
                    assertThat(((Number) row.get(1)).longValue()).isEqualTo(45));
        } finally {
            dropFixture(table);
        }
    }

    /**
     * 造一张 450 行的临时表：amount 只有 0~9 十个值，专门制造并列，验证主键兜底。
     */
    private void createFixture(String table) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, rootUser(), rootPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + table);
            statement.execute("CREATE TABLE " + table
                    + " (id BIGINT PRIMARY KEY, amount BIGINT NOT NULL, label VARCHAR(32) NOT NULL)");
        }
        try (Connection connection = DriverManager.getConnection(URL, rootUser(), rootPassword());
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO " + table + " (id, amount, label) VALUES (?, ?, ?)")) {
            for (int i = 1; i <= 450; i++) {
                int amount = i % 10;
                insert.setLong(1, i);
                insert.setLong(2, amount);
                insert.setString(3, amount + ":" + i);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private void dropFixture(String table) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, rootUser(), rootPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS " + table);
        }
    }

    private String rootUser() {
        return System.getProperty("ai.sql.business.username", "root");
    }

    private String rootPassword() {
        return System.getProperty("ai.sql.business.password", "123456");
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = executor.openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String currentUser(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return currentUser(connection);
        }
    }

    private String currentUser(Connection connection) throws SQLException {
        try (Connection closeable = connection;
             Statement statement = closeable.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT CURRENT_USER()")) {
            resultSet.next();
            return resultSet.getString(1);
        }
    }

    /**
     * 显式建执行器：不经过属性绑定，池与上限直接给。
     */
    @TestConfiguration
    static class SqlExecutorConfiguration {

        @Bean
        ReadOnlySqlExecutor readOnlySqlExecutor() {
            return executor("erp-ai-readonly-it");
        }
    }

    /**
     * 只装配取数连接池本身。
     */
    @TestConfiguration
    static class TestConfig {

        @Bean
        ReadOnlySqlExecutor aiSqlExecutor() {
            return executor("erp-ai-readonly-it");
        }
    }

    private static ReadOnlySqlExecutor executor(String poolName) {
        return executor(poolName, new SqlExecutionLimits(200, 2000, 10, 10));
    }

    private static ReadOnlySqlExecutor executor(String poolName, SqlExecutionLimits limits) {
        ReadOnlySqlPoolSettings settings = new ReadOnlySqlPoolSettings(URL, USERNAME, PASSWORD,
                "com.mysql.cj.jdbc.Driver", poolName, 1, 1, 2, 3000L, "SELECT 1", 1000L, true);
        return new ReadOnlySqlExecutor(new ReadOnlySqlPool(settings),
                limits,
                new SqlGuardGapReporter(mock(com.qiheng.erp.common.mq.SystemExceptionMqPublisher.class)));
    }
}
