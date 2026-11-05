package com.qiheng.erp.ai.sql.execution.model;

import java.util.List;

/**
 * 取数结果。
 *
 * @param columns       返回给模型的列（已剥掉翻页用的临时列）
 * @param rows          行数据，顺序与 {@code columns} 一致
 * @param rowCount      行数
 * @param completed     本次读取是否完成；SQL 自带 LIMIT 不代表取得全部业务数据
 * @param pages         实际执行了几页；非翻页路径为 1
 * @param elapsedMillis 耗时
 * @param sqlHash       原始 SQL 的哈希（审计用，不落库）
 * @author Li
 * @since 2026-10-26
 */
public record QueryResult(
        List<SqlQueryColumn> columns,
        List<List<Object>> rows,
        int rowCount,
        boolean completed,
        int pages,
        long elapsedMillis,
        String sqlHash,
        String truncationReason) {

    /** 兼容完整结果及未记录截断原因的调用。 */
    public QueryResult(List<SqlQueryColumn> columns, List<List<Object>> rows, int rowCount,
                       boolean completed, int pages, long elapsedMillis, String sqlHash) {
        this(columns, rows, rowCount, completed, pages, elapsedMillis, sqlHash, null);
    }

    public QueryResult {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }
}
