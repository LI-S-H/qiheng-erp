package com.qiheng.erp.ai.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AiConversationUpdateRequest {

    @NotBlank(message = "会话标题不能为空")
    @Size(max = 40, message = "会话标题不能超过40个字符")
    private String title;
}
