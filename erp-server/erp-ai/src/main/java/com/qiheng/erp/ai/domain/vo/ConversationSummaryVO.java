package com.qiheng.erp.ai.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 会话摘要：会话列表中的一项。
 *
 * @param conversationId 会话 ID
 * @param title          会话标题
 * @param description    会话摘要
 * @param updatedAt      最近更新时间
 * @author Li
 * @since 2026-10-15
 */
@Schema(description = "AI 会话摘要")
public record ConversationSummaryVO(
        @Schema(description = "会话 ID")
        String conversationId,

        @Schema(description = "会话标题")
        String title,

        @Schema(description = "会话摘要")
        String description,

        @Schema(description = "最近更新时间")
        String updatedAt
) {
}
