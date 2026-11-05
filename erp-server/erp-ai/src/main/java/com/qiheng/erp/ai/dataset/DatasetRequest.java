package com.qiheng.erp.ai.dataset;

/** 一次 SQL 执行的来源和归属，调用方须从服务端可信上下文构造。 */
public record DatasetRequest(String sqlRunId, long userId, long conversationId,
                             String description, String sql, int attempt) {
}
