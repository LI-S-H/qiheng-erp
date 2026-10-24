package com.qiheng.erp.ai.constant;

/**
 * 需求的运行期状态。
 *
 * <p>状态由代码节点维护，不进模型输出协议。</p>
 *
 * <p>{@code SKIPPED} 也算依赖满足，否则条件不成立时下游会永久卡住；
 * {@code BLOCKED} 不算依赖满足，且其下游一并标 {@code BLOCKED}；
 * {@code INFEASIBLE} 表示已尽力但结论不可行，与 {@code DONE} 并列。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
public enum NeedStatus {

    /** 尚未开始。 */
    PENDING,

    /** 正在取数。 */
    RUNNING,

    /** 已完成：完成判据全部满足。 */
    DONE,

    /** 条件不成立而跳过，算依赖满足。 */
    SKIPPED,

    /** 依赖受阻，连带标 blocked 的下游。 */
    BLOCKED,

    /** 已尽力但结论不可行。 */
    INFEASIBLE;

    /**
     * 是否终态。
     *
     * <p>终态需求不再占用"同时未完成的需求数"名额：一条需求跑完了，名额就该还回去。
     * 否则"先拆三条、跑完再补三条"这种正常节奏会被上限卡死，而报告层恰恰需要
     * "发现缺证据 → 补一条新需求"这条回路。</p>
     */
    public boolean terminal() {
        return this == DONE || this == SKIPPED || this == INFEASIBLE || this == BLOCKED;
    }
}
