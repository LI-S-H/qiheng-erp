package com.qiheng.erp.ai.service.data.sql.execution;

import com.qiheng.erp.ai.service.data.sql.guard.GuardedSql;
import com.alibaba.druid.pool.DruidPooledConnection;
import com.qiheng.erp.ai.service.data.sql.pool.ReadOnlySqlPool;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 翻页的契约：SQL 改写、游标方向、剥列、终止条件。
 *
 * @author Li
 * @since 2026-10-26
 */
class CursorPagerTest {

    private static final SqlExecutionLimits LIMITS = new SqlExecutionLimits(2, 10, 5, 5);

    // ==================== SQL 改写 ====================

    @Test
    void preparesCursorColumnsAndRebuildsOrderBy() {
        CursorPager.PreparedPages pages = CursorPager.prepare(guardedSql(
                "SELECT purchase_no FROM purchase_order WHERE deleted = 0 ORDER BY total_amount DESC",
                List.of(new GuardedSql.SortKey("total_amount", true))));

        assertThat(pages.injectedLabels()).containsExactly("__cursor_0", "__cursor_1");
        assertThat(pages.cursorLabels()).containsExactly("__cursor_0", "__cursor_1");
        assertThat(pages.firstPageSql())
                .contains("total_amount AS __cursor_0")
                .contains("id AS __cursor_1")
                .contains("ORDER BY total_amount DESC, id ASC")
                .doesNotContain("?");
        String nextPage = pages.nextPageSql().replaceAll("[\\s]+", " ");
        assertThat(nextPage)
                .contains("`total_amount` < ?")
                .contains("`total_amount` = ? AND `id` > ?")
                .contains("?");
        assertThat(pages.sortKeys())
                .containsExactly(new GuardedSql.SortKey("total_amount", true),
                        new GuardedSql.SortKey("id", false));
    }

    @Test
    void ascendingSortKeyUsesGreaterThan() {
        CursorPager.PreparedPages pages = CursorPager.prepare(guardedSql(
                "SELECT purchase_no FROM purchase_order WHERE deleted = 0 ORDER BY purchase_no ASC",
                List.of(new GuardedSql.SortKey("purchase_no", false))));

        assertThat(pages.nextPageSql()).contains("`purchase_no` > ?");
    }

    @Test
    void columnAlreadySelectedIsNotInjectedAgain() {
        CursorPager.PreparedPages pages = CursorPager.prepare(guardedSql(
                "SELECT total_amount, purchase_no FROM purchase_order "
                        + "WHERE deleted = 0 ORDER BY total_amount DESC",
                List.of(new GuardedSql.SortKey("total_amount", true))));

        assertThat(pages.cursorLabels()).containsExactly("total_amount", "__cursor_0");
        assertThat(pages.injectedLabels()).containsExactly("__cursor_0");
    }

    @Test
    void aliasedColumnIsReadThroughItsAlias() {
        CursorPager.PreparedPages pages = CursorPager.prepare(guardedSql(
                "SELECT total_amount AS amt FROM purchase_order WHERE deleted = 0 ORDER BY amt DESC",
                List.of(new GuardedSql.SortKey("total_amount", true))));

        assertThat(pages.cursorLabels()).containsExactly("amt", "__cursor_0");
        assertThat(pages.injectedLabels()).containsExactly("__cursor_0");
    }

    @Test
    void primaryKeyAlreadyInSortKeysIsNotDuplicated() {
        CursorPager.PreparedPages pages = CursorPager.prepare(guardedSql(
                "SELECT id FROM purchase_order WHERE deleted = 0 ORDER BY id DESC",
                List.of(new GuardedSql.SortKey("id", true))));

        assertThat(pages.sortKeys()).containsExactly(new GuardedSql.SortKey("id", true));
        assertThat(pages.cursorLabels()).containsExactly("id");
        assertThat(pages.injectedLabels()).isEmpty();
    }

    // ==================== 翻页执行 ====================

    @Test
    void fetchesAllPagesAndStripsCursorColumns() throws SQLException {
        ReadOnlySqlPool pool = mock(ReadOnlySqlPool.class);
        DruidPooledConnection connection = mock(DruidPooledConnection.class);
        when(pool.open()).thenReturn(connection);
        PreparedStatement first = mock(PreparedStatement.class);
        PreparedStatement second = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(first, second);
        ResultSet page1 = resultSet(new String[]{"purchase_no", "__cursor_0", "__cursor_1"},
                new String[][]{{"PO-1", "9000", "1001"}, {"PO-2", "8000", "1002"},
                        {"PO-3", "7000", "1003"}});
        ResultSet page2 = resultSet(new String[]{"purchase_no", "__cursor_0", "__cursor_1"},
                new String[][]{{"PO-3", "7000", "1003"}});
        when(first.executeQuery()).thenReturn(page1);
        when(second.executeQuery()).thenReturn(page2);

        ReadOnlySqlExecutor executor = new ReadOnlySqlExecutor(pool, LIMITS);
        ExecutionOutcome outcome = executor.execute(new SqlExecutionRequest("run", "step",
                guardedSql("SELECT purchase_no FROM purchase_order WHERE deleted = 0 "
                        + "ORDER BY total_amount DESC", List.of(new GuardedSql.SortKey("total_amount", true))),
                true));

        assertThat(outcome).isInstanceOf(ExecutionOutcome.Success.class);
        QueryResult result = ((ExecutionOutcome.Success) outcome).result();
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.pages()).isEqualTo(2);
        assertThat(result.completed()).isTrue();
        assertThat(result.truncated()).isFalse();
        assertThat(result.columns()).extracting(SqlQueryColumn::name).containsExactly("purchase_no");
        assertThat(result.rows()).allSatisfy(row -> assertThat(row).hasSize(1));
        assertThat(result.rows().get(0)).containsExactly("PO-1");

        // 第 2 页的书签 = 第 1 页最后一行的 (amount, id)
        verify(second).setObject(1, "8000");
        verify(second).setObject(2, "8000");
        verify(second).setObject(3, "1002");
    }

    @Test
    void stopsAtTotalRowCap() throws SQLException {
        ReadOnlySqlPool pool = mock(ReadOnlySqlPool.class);
        DruidPooledConnection connection = mock(DruidPooledConnection.class);
        when(pool.open()).thenReturn(connection);
        PreparedStatement first = mock(PreparedStatement.class);
        PreparedStatement second = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(first, second);
        ResultSet page1 = resultSet(new String[]{"purchase_no", "__cursor_0", "__cursor_1"},
                new String[][]{{"PO-1", "9000", "1001"}, {"PO-2", "8000", "1002"},
                        {"PO-3", "7000", "1003"}});
        ResultSet page2 = resultSet(new String[]{"purchase_no", "__cursor_0", "__cursor_1"},
                new String[][]{{"PO-3", "7000", "1003"}, {"PO-4", "6000", "1004"}});
        when(first.executeQuery()).thenReturn(page1);
        when(second.executeQuery()).thenReturn(page2);

        ReadOnlySqlExecutor executor = new ReadOnlySqlExecutor(pool,
                new SqlExecutionLimits(2, 3, 5, 5));
        ExecutionOutcome outcome = executor.execute(new SqlExecutionRequest("run", "step",
                guardedSql("SELECT purchase_no FROM purchase_order WHERE deleted = 0 "
                        + "ORDER BY total_amount DESC", List.of(new GuardedSql.SortKey("total_amount", true))),
                true));

        QueryResult result = ((ExecutionOutcome.Success) outcome).result();
        assertThat(result.rowCount()).isEqualTo(3);
        assertThat(result.completed()).isFalse();
        assertThat(result.truncated()).isTrue();
    }

    // ==================== 工具 ====================

    private GuardedSql guardedSql(String sql, List<GuardedSql.SortKey> sortKeys) {
        return new GuardedSql(sql, Set.of("purchase_order"), null, sortKeys, "purchase_order", "id");
    }

    /**
     * 造一个游标列全是 VARCHAR 的结果集；行值按列顺序给。
     */
    private ResultSet resultSet(String[] labels, String[][] rows) throws SQLException {
        ResultSet resultSet = mock(ResultSet.class);
        ResultSetMetaData meta = mock(ResultSetMetaData.class);
        when(resultSet.getMetaData()).thenReturn(meta);
        when(meta.getColumnCount()).thenReturn(labels.length);
        for (int i = 0; i < labels.length; i++) {
            when(meta.getColumnLabel(i + 1)).thenReturn(labels[i]);
            when(meta.getColumnName(i + 1)).thenReturn(labels[i]);
            when(meta.getColumnType(i + 1)).thenReturn(Types.VARCHAR);
            when(meta.getColumnTypeName(i + 1)).thenReturn("VARCHAR");
            when(meta.isNullable(i + 1)).thenReturn(ResultSetMetaData.columnNullable);
        }
        AtomicInteger cursor = new AtomicInteger(-1);
        when(resultSet.next()).thenAnswer(invocation -> cursor.incrementAndGet() < rows.length);
        when(resultSet.getObject(anyInt())).thenAnswer(invocation -> {
            int column = invocation.getArgument(0);
            return rows[cursor.get()][column - 1];
        });
        return resultSet;
    }
}