package com.qiheng.erp.ai.dataset;

import com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn;

import java.util.List;
import java.time.Instant;

/** 数据集元信息；完整数据保存在文件中，路径和 SQL 不发送给模型。 */
public record DatasetRecord(String datasetId, String sqlRunId, long userId,
                            long conversationId, String description, List<SqlQueryColumn> columns,
                            int rowCount, boolean completed, String status, Instant expiresAt,
                            SqlDataDescription dataDescription, String truncationReason) {
    /** 保留没有截断原因的历史记录。 */
    public DatasetRecord(String datasetId, String sqlRunId, long userId, long conversationId,
                         String description, List<SqlQueryColumn> columns, int rowCount, boolean completed,
                         String status, Instant expiresAt, SqlDataDescription dataDescription) {
        this(datasetId, sqlRunId, userId, conversationId, description, columns, rowCount,
                completed, status, expiresAt, dataDescription, null);
    }

    /** 描述查询读取的完整性；历史记录没有原因时不推测。 */
    public String completenessMessage() {
        if (completed) return "数据集已完整读取。";
        String reason = switch (truncationReason == null ? "" : truncationReason) {
            case "ROW_LIMIT" -> "达到行数上限";
            case "PAGE_LIMIT" -> "达到分页上限";
            default -> "查询未完整读取";
        };
        return "数据集因" + reason + "而不完整，完整总行数未知，不能代表全部数据；无明确排序时不能认为是最新数据。可依据已知条件缩小范围或分步查询，不得编造条件。";
    }
}
