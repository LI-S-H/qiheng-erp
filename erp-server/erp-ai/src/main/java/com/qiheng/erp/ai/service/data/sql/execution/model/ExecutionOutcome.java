package com.qiheng.erp.ai.service.data.sql.execution.model;

/**
 * 取数结局：成功、要模型补声明、失败。
 *
 * @author Li
 * @since 2026-10-26
 */
public sealed interface ExecutionOutcome permits ExecutionOutcome.Success,
        ExecutionOutcome.NeedsDeclaration, ExecutionOutcome.Failed {

    /**
     * 拿到结果。
     *
     * @param result 结果集
     */
    record Success(QueryResult result) implements ExecutionOutcome {
    }

    /**
     * 模型没说清要多少行：没写 LIMIT，但结果超过了页大小。
     *
     * <p>不是错误，是"你得先告诉我要全部还是固定条数"。</p>
     *
     * @param message 给模型看的说明
     */
    record NeedsDeclaration(String message) implements ExecutionOutcome {
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