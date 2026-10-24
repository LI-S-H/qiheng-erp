package com.qiheng.erp.ai.constant;

/**
 * AI 消息种类：与 {@code ai_message.kind} 取值一致。
 *
 * <p>用户可见消息只有 {@link #USER_INPUT} 与 {@link #ASSISTANT_FINAL}；
 * 节点中间步骤、工具结果等只进节点记忆，不出现在用户可见分页里。</p>
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

    private AiMessageKind() {
    }
}
