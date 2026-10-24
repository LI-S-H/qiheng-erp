package com.qiheng.erp.ai.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * AI 助手概览：页面首屏数据。
 *
 * <p>当前阶段快捷任务尚未实现，{@code quickPrompts} 固定返回空数组，
 * 不展示未落地的业务分析入口。</p>
 *
 * @param refreshedAt   概览刷新时间，服务端生成
 * @param quickPrompts  快捷任务入口，当前为空数组
 * @param conversations 当前用户最近的会话摘要
 * @author Li
 * @since 2026-10-15
 */
@Schema(description = "AI 助手概览")
public record AssistantOverviewVO(
        @Schema(description = "概览刷新时间") String refreshedAt,
        @Schema(description = "快捷任务入口，当前为空数组") List<Object> quickPrompts,
        @Schema(description = "最近会话摘要") List<ConversationSummaryVO> conversations
) { }
