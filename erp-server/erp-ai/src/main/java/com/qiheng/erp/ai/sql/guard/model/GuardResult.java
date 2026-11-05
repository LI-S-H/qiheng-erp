package com.qiheng.erp.ai.sql.guard.model;

/**
 * 守卫结论：放行或拒绝。
 *
 * @author Li
 * @since 2026-10-25
 */
public sealed interface GuardResult permits GuardResult.Ok, GuardResult.Rejected {

    /**
     * 放行：带着守卫核验过的结构事实。
     *
     * @param guardedSql 已核验的 SQL
     */
    record Ok(GuardedSql guardedSql) implements GuardResult {
    }

    /**
     * 拒绝：拒因码 + 可直接喂回模型的一句话。
     *
     * @param code    拒因码
     * @param message 给模型看的说明
     */
    record Rejected(SqlGuardRejectCode code, String message) implements GuardResult {
    }
}