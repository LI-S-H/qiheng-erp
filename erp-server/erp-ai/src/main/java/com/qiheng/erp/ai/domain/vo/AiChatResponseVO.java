package com.qiheng.erp.ai.domain.vo;

import lombok.Data;

@Data
public class AiChatResponseVO {
    private Long conversationId;
    private AiChatMessageVO message;
}
