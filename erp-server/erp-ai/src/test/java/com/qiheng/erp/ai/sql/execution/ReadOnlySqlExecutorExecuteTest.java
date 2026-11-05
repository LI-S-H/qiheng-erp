package com.qiheng.erp.ai.sql.execution;

import com.qiheng.erp.ai.sql.execution.model.SqlExecutionRequest;
import com.qiheng.erp.ai.sql.guard.model.GuardedSql;
import com.qiheng.erp.ai.sql.execution.model.ExecutionOutcome;
import com.qiheng.erp.ai.sql.execution.model.QueryResult;
import com.qiheng.erp.ai.sql.execution.model.SqlErrorKind;
import com.qiheng.erp.ai.sql.execution.model.SqlExecutionLimits;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.qiheng.erp.ai.sql.pool.ReadOnlySqlPool;
import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;
import com.qiheng.erp.ai.sql.guard.DruidSqlGuard;
import com.qiheng.erp.ai.sql.guard.model.GuardResult;
import com.qiheng.erp.ai.sql.guard.model.SqlGuardLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 执行器三条路径与错误分类的契约。
 *
 * @author Li
 * @since 2026-10-26
 */
class ReadOnlySqlExecutorExecuteTest {

    private static final SqlExecutionLimits LIMITS = new SqlExecutionLimits(2, 10, 5, 5);

    private final SqlGuardGapReporter guardGapReporter = mock(SqlGuardGapReporter.class);

    @Test
    void limitPathReturnsExactlyWhatWasAsked() throws SQLException {
        ReadOnlySqlExecutor executor = executor(resultSet(3));

        QueryResult result = success(executor, guardedSql("SELECT id, name FROM t LIMIT 2", 2), false);

        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.completed()).isTrue();
        assertThat(result.completed()).isTrue();
        assertThat(result.pages()).isEqualTo(1);
    }

    @Test
    void boundedPathPassesWhenResultFitsTotalLimit() throws SQLException {
        ReadOnlySqlExecutor executor = executor(resultSet(2));

        QueryResult result = success(executor, guardedSql("SELECT id FROM t", null), false);

        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.completed()).isTrue();
    }

    @Test
    void boundedPathReadsBeyondPageSizeWhenWithinTotalLimit() throws SQLException {
        ReadOnlySqlExecutor executor = executor(resultSet(3));

        ExecutionOutcome outcome = executor.execute(new SqlExecutionRequest("run", "step",
                guardedSql("SELECT id FROM t", null), false));

        assertThat(outcome).isInstanceOf(ExecutionOutcome.Success.class);
        assertThat(((ExecutionOutcome.Success) outcome).result().rowCount()).isEqualTo(3);
    }

    /** 分组查询不论模型是否声明全量，均使用原 SQL 而不追加 id 游标。 */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void groupByReadsOriginalSqlWithinTotalLimit(boolean needFullData) throws SQLException {
        String sql = "SELECT id, COUNT(*) AS total FROM t GROUP BY id";
        var schema = AuthorizedSchemaSnapshot.of("t(id)",
                List.of(new AuthorizedSchemaSnapshot.Table("t", "测试表", "test", false, "id")),
                Map.of("t", List.of(new AuthorizedSchemaSnapshot.Column("id", false, true))));
        var checked = new DruidSqlGuard().check(sql, schema, new SqlGuardLimits(10), needFullData);
        assertThat(checked).isInstanceOf(GuardResult.Ok.class);
        GuardedSql guarded = ((GuardResult.Ok) checked).guardedSql();
        ReadOnlySqlPool pool = mock(ReadOnlySqlPool.class);
        DruidPooledConnection connection = mock(DruidPooledConnection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(pool.open()).thenReturn(connection);
        when(connection.prepareStatement(sql)).thenReturn(statement);
        ResultSet groupedRows = resultSet(3);
        when(statement.executeQuery()).thenReturn(groupedRows);
        var executor = new ReadOnlySqlExecutor(pool, LIMITS, guardGapReporter);

        var outcome = executor.execute(new SqlExecutionRequest("run", "step", guarded, needFullData));
        assertThat(outcome).isInstanceOf(ExecutionOutcome.Success.class);
        assertThat(((ExecutionOutcome.Success) outcome).result().rowCount()).isEqualTo(3);
        verify(connection).prepareStatement(sql);
        verify(statement).setMaxRows(11);
    }

    /** 最大值本身完整，多一行则返回截断结果并说明原因。 */
    @Test
    void boundedReadDetectsExactLimitAndOverflow() throws SQLException {
        String sql = "SELECT id FROM t";
        var allowed = executor(resultSet(10)).execute(new SqlExecutionRequest("run", "step", guardedSql(sql, null), false));
        assertThat(allowed).isInstanceOf(ExecutionOutcome.Success.class);
        assertThat(((ExecutionOutcome.Success) allowed).result().rowCount()).isEqualTo(10);
        assertThat(((ExecutionOutcome.Success) allowed).result().completed()).isTrue();
        var exceeded = executor(resultSet(11)).execute(new SqlExecutionRequest("run", "step", guardedSql(sql, null), false));
        assertThat(exceeded).isInstanceOf(ExecutionOutcome.Success.class);
        var result = ((ExecutionOutcome.Success) exceeded).result();
        assertThat(result.rowCount()).isEqualTo(10);
        assertThat(result.completed()).isFalse();
        assertThat(result.truncationReason()).isEqualTo("ROW_LIMIT");
    }

    @Test
    void timeoutIsClassified() throws SQLException {
        ExecutionOutcome outcome = executorThrowing(new SQLTimeoutException("timeout"))
                .execute(request(guardedSql("SELECT id FROM t", 2)));

        assertThat(failed(outcome).kind()).isEqualTo(SqlErrorKind.TIMEOUT);
    }

    @Test
    void syntaxErrorCarriesDatabaseDetail() throws SQLException {
        ExecutionOutcome outcome = executorThrowing(
                new SQLSyntaxErrorException("Unknown column 'foo'", "42S22", 1054))
                .execute(request(guardedSql("SELECT foo FROM t", 2)));

        ExecutionOutcome.Failed failed = failed(outcome);
        assertThat(failed.kind()).isEqualTo(SqlErrorKind.SYNTAX);
        assertThat(failed.modelMessage()).contains("Unknown column 'foo'");
        assertThat(failed.userMessage()).doesNotContain("Unknown column");
    }

    @Test
    void permissionErrorIsClassifiedByCodeBeforeExceptionType() throws SQLException {
        // MySQL 的权限错误 SQLState 也是 42000，会被包装成 SQLSyntaxErrorException
        ExecutionOutcome outcome = executorThrowing(
                new SQLSyntaxErrorException("SELECT command denied", "42000", 1142))
                .execute(request(guardedSql("SELECT id FROM t", 2)));

        assertThat(failed(outcome).kind()).isEqualTo(SqlErrorKind.PERMISSION);
    }

    @Test
    void wallRejectionIsClassified() throws SQLException {
        ExecutionOutcome outcome = executorThrowing(
                new SQLSyntaxErrorException("sql injection violation, comment not allow", "42000", 1000))
                .execute(request(guardedSql("SELECT id FROM t", 2)));

        ExecutionOutcome.Failed failed = failed(outcome);
        assertThat(failed.kind()).isEqualTo(SqlErrorKind.WALL_REJECTED);
        // 模型要看到翻译后的原因，不能只看到"触发了安全拦截"
        assertThat(failed.modelMessage()).contains("不能写注释");
        verify(guardGapReporter).reportWallRejected(eq("run"), eq("step"), anyString(),
                contains("sql injection"));
    }

    @Test
    void authenticationFailureIsItsOwnKind() throws SQLException {
        // 1045 是认证失败，不是"没权限查这张表"；SQLState 也是 28000
        ExecutionOutcome outcome = executorThrowing(
                new SQLSyntaxErrorException("Access denied for user 'erp_ai_ro'", "28000", 1045))
                .execute(request(guardedSql("SELECT id FROM t", 2)));

        assertThat(failed(outcome).kind()).isEqualTo(SqlErrorKind.AUTHENTICATION);
        assertThat(failed(outcome).userMessage()).contains("认证失败");
    }

    @Test
    void columnLevelPermissionIsClassifiedAsPermission() throws SQLException {
        ExecutionOutcome outcome = executorThrowing(
                new SQLSyntaxErrorException("SELECT command denied for column", "42000", 1143))
                .execute(request(guardedSql("SELECT id FROM t", 2)));

        assertThat(failed(outcome).kind()).isEqualTo(SqlErrorKind.PERMISSION);
    }

    @Test
    void connectionErrorIsClassified() throws SQLException {
        ExecutionOutcome outcome = executorThrowing(
                new SQLException("connection lost", "08S01", 0))
                .execute(request(guardedSql("SELECT id FROM t", 2)));

        assertThat(failed(outcome).kind()).isEqualTo(SqlErrorKind.CONNECTION);
    }

    // ==================== 工具 ====================

    private ReadOnlySqlExecutor executor(ResultSet resultSet) throws SQLException {
        ReadOnlySqlPool pool = mock(ReadOnlySqlPool.class);
        DruidPooledConnection connection = mock(DruidPooledConnection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(pool.open()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        return new ReadOnlySqlExecutor(pool, LIMITS, guardGapReporter);
    }

    private ReadOnlySqlExecutor executorThrowing(SQLException error) throws SQLException {
        ReadOnlySqlPool pool = mock(ReadOnlySqlPool.class);
        DruidPooledConnection connection = mock(DruidPooledConnection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(pool.open()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenThrow(error);
        return new ReadOnlySqlExecutor(pool, LIMITS, guardGapReporter);
    }

    private SqlExecutionRequest request(GuardedSql guardedSql) {
        return new SqlExecutionRequest("run", "step", guardedSql, false);
    }

    private QueryResult success(ReadOnlySqlExecutor executor, GuardedSql guardedSql,
                                boolean needFullData) {
        ExecutionOutcome outcome = executor.execute(new SqlExecutionRequest("run", "step", guardedSql,
                needFullData));
        assertThat(outcome).isInstanceOf(ExecutionOutcome.Success.class);
        return ((ExecutionOutcome.Success) outcome).result();
    }

    private ExecutionOutcome.Failed failed(ExecutionOutcome outcome) {
        assertThat(outcome).isInstanceOf(ExecutionOutcome.Failed.class);
        return (ExecutionOutcome.Failed) outcome;
    }

    private GuardedSql guardedSql(String sql, Integer limit) {
        return new GuardedSql(sql, Set.of("t"), limit, List.of(), null, null);
    }

    /**
     * 造一个只有 id 列、给定行数的结果集。
     */
    private ResultSet resultSet(int rowCount) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(resultSet.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(1);
        when(meta.getColumnLabel(1)).thenReturn("id");
        when(meta.getColumnName(1)).thenReturn("id");
        when(meta.getColumnType(1)).thenReturn(Types.BIGINT);
        when(meta.getColumnTypeName(1)).thenReturn("BIGINT");
        when(meta.isNullable(1)).thenReturn(ResultSetMetaData.columnNoNulls);
        AtomicInteger cursor = new AtomicInteger(0);
        when(resultSet.next()).thenAnswer(invocation -> cursor.getAndIncrement() < rowCount);
        when(resultSet.getObject(anyInt())).thenAnswer(invocation -> (long) cursor.get());
        return resultSet;
    }
}
