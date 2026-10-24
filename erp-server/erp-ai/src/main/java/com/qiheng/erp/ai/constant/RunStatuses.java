package com.qiheng.erp.ai.constant;

/**
 * 对前端公开的运行状态取值：由活动任务、检查点和展示流水共同推导，集中定义避免字面量散落。
 *
 * <p>与轮次落库状态 {@link MessageRunStatus} 和需求层路由值
 * {@link NeedCheckResult} 是不同层面的概念，不要混用。</p>
 *
 * @author Li
 * @since 2026-10-16
 */
public final class RunStatuses {

    /** 没有在跑的任务，也没有待继续的暂停点。 */
    public static final String IDLE = "IDLE";

    /** 正在执行。 */
    public static final String RUNNING = "RUNNING";

    /** 本轮执行失败。 */
    public static final String FAILED = "FAILED";

    /** 本轮曾在暂停点中断，且无法继续恢复。 */
    public static final String INTERRUPTED = "INTERRUPTED";

    /** 需求数组已就绪，等待后续执行器接入。 */
    public static final String NEEDS_READY = "NEEDS_READY";

    /** 等待用户补充信息。 */
    public static final String WAITING_HUMAN = "WAITING_HUMAN";

    /** 格式修复预算耗尽，本轮规划未完成。 */
    public static final String FORMAT_RETRY_EXHAUSTED = "FORMAT_RETRY_EXHAUSTED";

    private RunStatuses() {
    }
}
