package com.qiheng.erp.ai.service.data.sql.execution;

import com.qiheng.erp.ai.service.data.sql.execution.model.SqlExecutionRequest;
import com.qiheng.erp.ai.service.data.sql.guard.model.GuardedSql;
import com.qiheng.erp.ai.service.data.sql.execution.model.ExecutionOutcome;
import com.qiheng.erp.ai.service.data.sql.execution.model.QueryResult;
import com.qiheng.erp.ai.service.data.sql.execution.model.SqlErrorKind;
import com.qiheng.erp.ai.service.data.sql.execution.model.SqlExecutionLimits;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.qiheng.erp.ai.service.data.sql.pool.ReadOnlySqlPool;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.Types;
import java.util.List;
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
        assertThat(result.truncated()).isFalse();
        assertThat(result.completed()).isTrue();
        assertThat(result.pages()).isEqualTo(1);
    }

    @Test
    void probePathPassesWhenResultFitsPageSize() throws SQLException {
        ReadOnlySqlExecutor executor = executor(resultSet(2));

        QueryResult result = success(executor, guardedSql("SELECT id FROM t", null), false);

        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.completed()).isTrue();
    }

    @Test
    void probePathAsksForDeclarationWhenResultExceedsPageSize() throws SQLException {
        ReadOnlySqlExecutor executor = executor(resultSet(3));

        ExecutionOutcome outcome = executor.execute(new SqlExecutionRequest("run", "step",
                guardedSql("SELECT id FROM t", null), false));

        assertThat(outcome).isInstanceOf(ExecutionOutcome.NeedsDeclaration.class);
        assertThat(((ExecutionOutcome.NeedsDeclaration) outcome).message())
                .contains("超过 2 行").contains("需要全量").contains("LIMIT");
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

        assertThat(failed(outcome).kind()).isEqualTo(SqlErrorKind.WALL_REJECTED);
        verify(guardGapReporter).reportWallRejected(eq("run"), eq("step"), anyString(),
                contains("sql injection"));
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