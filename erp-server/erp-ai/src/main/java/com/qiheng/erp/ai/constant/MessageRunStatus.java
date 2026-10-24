package com.qiheng.erp.ai.constant;

/**
 * 轮次执行状态：仅 {@code USER_INPUT} 消息行承载。
 *
 * <p>状态更新不覆盖消息原文；轮次失败时只允许
 * {@link #RUNNING} 转为 {@link #FAILED} / {@link #CANCELLED} / {@link #INTERRUPTED}。</p>
 *
 * <p>与 {@link RunStatuses} 刻意区分：那是<b>对外</b>的运行态（给前端看，含
 * {@code PLAN_READY}、{@code WAITING_HUMAN} 等图语义），本类是<b>落库</b>的
 * 轮次状态（{@code ai_message.run_status} 列的取值）。两个类名相近、且都有
 * {@code RUNNING}、{@code FAILED}、{@code INTERRUPTED}，历史上极易混用，
 * 因此本类改名为 {@code MessageRunStatus} 以示区分。</p>
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
