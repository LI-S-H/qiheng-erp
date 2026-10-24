package com.qiheng.erp.ai.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 发起独立图任务，用户身份由登录上下文取得。
 *
 * @param conversationId 任务归属的会话 ID
 * @param query          本次任务需求
 * @author Li
 * @since 2026-10-08
 */
public record GraphStartRequest(
        @NotNull(message = "会话 ID 不能为空")
        @Schema(description = "任务归属的会话 ID")
        Long conversationId,

        @NotBlank
        @Size(max = 10000)
        @Schema(description = "本次任务需求；必须包含非空白字符，不传登录用户身份")
        String query
) {
}
