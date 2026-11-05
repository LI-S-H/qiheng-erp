package com.qiheng.erp.ai.sql.graph.model.output;

/** 模型正文不符合 SQL 节点输出协议，可由节点按预算请求修正。 */
public final class InvalidSqlModelOutput extends RuntimeException {
    public InvalidSqlModelOutput(String message) {
        super(message);
    }
}
