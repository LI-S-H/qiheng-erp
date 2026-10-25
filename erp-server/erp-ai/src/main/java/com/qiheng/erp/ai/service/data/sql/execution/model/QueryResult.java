package com.qiheng.erp.ai.service.data.sql.execution.model;

import java.util.List;

/**
 * 取数结果。
 *
 * @param columns       返回给模型的列（已剥掉翻页用的临时列）
 * @param rows          行数据，顺序与 {@code columns} 一致
 * @param rowCount      行数
 * @param truncated     是否撞到了**我们自己**的上限（模型自己写的 LIMIT 不算）
 * @param completed     翻页是否取完；非翻页路径恒为 true
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
        boolean truncated,
        boolean completed,
        int pages,
        long elapsedMillis,
        String sqlHash) {

    public QueryResult {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }
}