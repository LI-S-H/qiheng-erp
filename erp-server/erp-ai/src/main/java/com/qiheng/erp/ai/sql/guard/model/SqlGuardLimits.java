package com.qiheng.erp.ai.sql.guard.model;

/**
 * 守卫的上限参数。
 *
 * @param maxTotalRows 单个证据项允许的最大行数（LIMIT 超过它直接拒绝）
 * @author Li
 * @since 2026-10-25
 */
public record SqlGuardLimits(int maxTotalRows) {
}