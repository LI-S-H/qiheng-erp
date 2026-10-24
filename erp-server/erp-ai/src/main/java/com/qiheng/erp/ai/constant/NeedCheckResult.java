package com.qiheng.erp.ai.constant;

/**
 * 需求层节点的路由取值。
 *
 * <p>同时作为条件边的路由值，避免重复维护 nextNode 标志。
 * 与 {@link RunStatuses}（对外运行态）、{@link MessageRunStatus}（落库轮次态）
 * 是不同层面的概念，不要混用。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
public enum NeedCheckResult {

    /** 产出不合规，回 planner 修复。 */
    RETRY_FORMAT,

    /** 缺少必须由用户确认的信息，暂停等待补充。 */
    WAITING_HUMAN,

    /** 需求数组通过结构校验，进入业务完整性审阅。 */
    READY,

    /** 修复预算耗尽，本轮规划未完成。 */
    FORMAT_RETRY_EXHAUSTED
}
