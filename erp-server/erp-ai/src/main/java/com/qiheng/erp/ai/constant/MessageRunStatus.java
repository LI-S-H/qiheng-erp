package com.qiheng.erp.ai.constant;

/**
 * 轮次执行状态：仅 {@code USER_INPUT} 消息行承载。
 *
 * <p>状态更新不覆盖消息原文；轮次失败时只允许
 * {@link #RUNNING} 转为 {@link #FAILED} / {@link #CANCELLED} / {@link #INTERRUPTED}。</p>
 *
 * <p>本类保留历史消息的落库状态，不承担新研究的状态模型。
 * 旧规划的对外运行态已随编排退役；新研究状态将在后续模块中单独设计。</p>
 *
 * @author Li
 * @since 2026-10-15
 */
public final class MessageRunStatus {

    public static final String RUNNING = "RUNNING";

    public static final String COMPLETED = "COMPLETED";

    public static final String FAILED = "FAILED";

    public static final String CANCELLED = "CANCELLED";

    public static final String INTERRUPTED = "INTERRUPTED";

    private MessageRunStatus() {
    }
}
