package com.qiheng.erp.ai.dataset;

import com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn;
import java.util.List;

/** 数据集读取结果；hasMore 表示仍有数据，truncated 仅表示触及文本预算。 */
public record DatasetPage(List<SqlQueryColumn> columns, List<List<Object>> rows,
                          int rowCount, int returnedRows, boolean hasMore,
                          boolean truncated, Integer nextOffset, boolean datasetTruncated, String completenessMessage) {
    /** 兼容内部预览及既有调用。 */
    public DatasetPage(List<SqlQueryColumn> columns, List<List<Object>> rows,
                       int rowCount, int returnedRows, boolean hasMore) {
        this(columns, rows, rowCount, returnedRows, hasMore, false, null, false, "数据集已完整读取。");
    }
}
