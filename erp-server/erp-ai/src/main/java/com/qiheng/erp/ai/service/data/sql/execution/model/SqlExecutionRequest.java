package com.qiheng.erp.ai.service.data.sql.execution.model;

import com.qiheng.erp.ai.service.data.sql.guard.model.GuardedSql;

/**
 * 一次取数请求。
 *
 * <p>只接受 {@link GuardedSql}：没通过守卫的 SQL 在类型上就进不来。</p>
 *
 * @param runId        本轮运行标识（日志与审计用）
 * @param stepId       当前步骤标识
 * @param guardedSql   守卫放行后的 SQL
 * @param needFullData 模型是否声明"需要全量明细"
 * @author Li
 * @since 2026-10-26
 */
public record SqlExecutionRequest(String runId, String stepId, GuardedSql guardedSql,
                                  boolean needFullData) {
}