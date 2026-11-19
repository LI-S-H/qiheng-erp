package com.qiheng.erp.ai.constant;

/**
 * AI 消息种类：与 {@code ai_message.kind} 取值一致。
 *
 * <p>用户可见分页只读取 {@code visible=1} 的行：{@link #USER_INPUT}、{@link #ASSISTANT_FINAL}，
 * 展示用的过程行 {@link #ASSISTANT_STEP} 与 {@link #ANALYSIS_STEP}，以及 ask_user 的调用与结果。
 * 其他工具的调用与结果以 {@code visible=0} 保存，页面不渲染；它们的完整原文记在
 * {@code ai_agent_trace} 流水里。</p>
 *
 * @author Li
 * @since 2026-10-15
 */
public final class AiMessageKind {

    /** 用户输入。 */
    public static final String USER_INPUT = "USER_INPUT";

    /** 助手中间步骤，如 Planner 思考过程、计划版本。 */
    public static final String ASSISTANT_STEP = "ASSISTANT_STEP";

    /** 工具调用结果。 */
    public static final String TOOL_RESULT = "TOOL_RESULT";

    /** 助手最终回复，只展示这一类给用户。 */
    public static final String ASSISTANT_FINAL = "ASSISTANT_FINAL";

    /** 分析子过程；由分析面板读取，不进入聊天消息流。 */
    public static final String ANALYSIS_STEP = "ANALYSIS_STEP";

    /** 报告生成与审阅的用户可见过程。 */
    public static final String REPORT_STEP = "REPORT_STEP";

    private AiMessageKind() {
    }
}
