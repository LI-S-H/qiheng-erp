package com.qiheng.erp.ai.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 会话历史消息分页结果。
 *
 * @param records  当前页消息
 * @param total    该会话未删除的可见消息总数
 * @param pageNum  当前页码，从 1 开始
 * @param pageSize 每页条数
 * @author Li
 * @since 2026-10-15
 */
@Schema(description = "AI 会话历史消息分页")
public record ConversationMessagePageVO(
        @Schema(description = "当前页消息") List<ChatMessageVO> records,
        @Schema(description = "可见消息总数") long total,
        @Schema(description = "当前页码") int pageNum,
        @Schema(description = "每页条数") int pageSize
) {
}
