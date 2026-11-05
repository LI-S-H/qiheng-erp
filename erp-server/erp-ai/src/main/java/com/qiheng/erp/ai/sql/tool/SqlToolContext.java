package com.qiheng.erp.ai.sql.tool;

/** 服务端认证后提供给 SQL 工具的身份与会话，不属于模型输入。 */
public record SqlToolContext(long userId, long conversationId, String businessDate) {
    public SqlToolContext(long userId, long conversationId) {
        this(userId, conversationId, null);
    }
}
