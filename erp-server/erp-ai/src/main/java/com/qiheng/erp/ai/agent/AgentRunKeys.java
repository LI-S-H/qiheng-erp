package com.qiheng.erp.ai.agent;

/**
 * 主智能体运行配置中的键名。
 *
 * @author Li
 * @since 2026-11-10
 */
public final class AgentRunKeys {

    /** 工具事件回调；工具与分析过程都通过它把事件送入运行事件流。 */
    public static final String TOOL_EVENTS = "toolEvents";

    /** 工具调用记录回调；在工具真正执行前保存调用信息。 */
    public static final String RECORD_TOOL_CALL = "recordToolCall";

    private AgentRunKeys() {
    }
}
