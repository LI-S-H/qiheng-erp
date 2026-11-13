package com.qiheng.erp.ai.context;

/**
 * 服务端认证后提供给各智能体工具的身份与业务日期，不属于模型输入。
 *
 * @author Li
 * @since 2026-11-10
 */
public record AgentToolContext(long userId, long conversationId, String businessDate) {
    /** 保留既有运行上下文键，确保各工具使用同一可信身份。 */
    public static final String CONTEXT_KEY = "sqlToolContext";

    public AgentToolContext(long userId, long conversationId) {
        this(userId, conversationId, null);
    }
}
