package com.qiheng.erp.ai.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/** 会话基础信息；消息历史通过分页接口读取。 */
@Schema(description = "AI 会话详情")
public record ConversationDetailVO(String conversationId, String title, String description, String updatedAt) {}
