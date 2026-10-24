package com.qiheng.erp.ai.domain.vo;

/** 用户可见消息；所有数据库编号按字符串返回，避免浏览器精度损失。 */
public record ChatMessageVO(
        String messageId,
        String requestId,
        String seq,
        String role,
        String kind,
        VisibleMessageContent content,
        String createdAt
) {
}
