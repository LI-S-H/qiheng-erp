package com.qiheng.erp.ai.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * AI 助手概览：页面首屏数据。
 *
 * @param refreshedAt   概览刷新时间，服务端生成
 * @param conversations 当前用户最近的会话摘要
 * @author Li
 * @since 2026-10-15
 */
@Schema(description = "AI 助手概览")
public record AssistantOverviewVO(
        @Schema(description = "概览刷新时间") String refreshedAt,
        @Schema(description = "最近会话摘要") List<ConversationSummaryVO> conversations
) { }
