package com.qiheng.erp.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** pauseId 绑定当前问题，旧页面提交不能恢复新的暂停轮次。 */
public record GraphResumeRequest(
        @NotBlank
        @Size(max = 160)
        String threadId,

        @NotBlank
        @Size(max = 64)
        String pauseId,

        @NotBlank
        @Size(max = 10000)
        String answer
) {
}
