package com.qiheng.erp.ai.service.data.sql.execution;

import com.qiheng.erp.ai.service.data.sql.guard.model.GuardedSql;
import com.qiheng.erp.ai.service.data.sql.execution.model.ExecutionOutcome;
import com.qiheng.erp.ai.service.data.sql.execution.model.QueryResult;
import com.qiheng.erp.ai.service.data.sql.execution.model.SqlExecutionLimits;
import com.qiheng.erp.ai.service.data.sql.execution.model.SqlExecutionRequest;
import com.qiheng.erp.ai.service.data.sql.execution.model.SqlQueryColumn;
import com.qiheng.erp.ai.service.data.sql.pool.ReadOnlySqlPool;
import com.qiheng.erp.ai.service.data.sql.pool.ReadOnlySqlPoolSettings;
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
            assertThat(result.truncated()).isFalse();
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
        ReadOnlySqlPoolSettings settings = new ReadOnlySqlPoolSettings(URL, USERNAME, PASSWORD,
                "com.mysql.cj.jdbc.Driver", poolName, 1, 1, 2, 3000L, "SELECT 1", 1000L, true);
        return new ReadOnlySqlExecutor(new ReadOnlySqlPool(settings),
                new SqlExecutionLimits(200, 5000, 10, 10),
                new SqlGuardGapReporter(mock(com.qiheng.erp.common.mq.SystemExceptionMqPublisher.class)));
    }
}
