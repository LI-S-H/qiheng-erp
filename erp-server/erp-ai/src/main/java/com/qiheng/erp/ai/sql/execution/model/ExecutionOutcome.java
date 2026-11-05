package com.qiheng.erp.ai.sql.execution.model;

/**
 * 取数结局：成功、要模型补声明、失败。
 *
 * @author Li
 * @since 2026-10-26
 */
public sealed interface ExecutionOutcome permits ExecutionOutcome.Success,
        ExecutionOutcome.LimitExceeded, ExecutionOutcome.IncompleteResult, ExecutionOutcome.Failed {

    /**
     * 拿到结果。
     *
     * @param result 结果集
     */
    record Success(QueryResult result) implements ExecutionOutcome {
    }

    /** 结果超过安全上限，未交付部分数据。 */
    record LimitExceeded(int maxRows) implements ExecutionOutcome {
    }

    /** 分页在读取完成前触及页数或行数上限。 */
    record IncompleteResult() implements ExecutionOutcome {
    }

    /**
     * 执行失败。
     *
     * @param kind         错误分类
     * @param modelMessage 给模型看的说明（可带数据库细节，能指导改写）
     * @param userMessage  给用户看的说明（脱敏）
     */
    record Failed(SqlErrorKind kind, String modelMessage, String userMessage)
            implements ExecutionOutcome {
    }
}
