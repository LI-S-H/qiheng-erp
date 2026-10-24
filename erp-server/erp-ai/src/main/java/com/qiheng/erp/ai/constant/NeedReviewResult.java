package com.qiheng.erp.ai.constant;

/**
 * 业务完整性审阅结论，同时作为条件边的路由值。
 *
 * @author Li
 * @since 2026-10-22
 */
public enum NeedReviewResult {

    /** 需求拆得完整，进入需求选择。 */
    OK,

    /** 存在业务遗漏，回 planner 修复。 */
    ISSUES,

    /** 审阅输出不合规，回本节点重问一次。 */
    RETRY_FORMAT,

    /** 修复预算耗尽，本轮规划未完成。 */
    FORMAT_RETRY_EXHAUSTED
}
