package com.qiheng.erp.ai.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 重命名会话请求体。
 *
 * @param title 新标题
 * @author Li
 * @since 2026-10-15
 */
@Schema(description = "重命名 AI 会话")
public record ConversationUpdateRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 40, message = "标题不能超过40字符")
        @Schema(description = "会话标题", maxLength = 40)
        String title
) {
}
