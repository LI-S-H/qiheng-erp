package com.qiheng.erp.ai.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiChatMessageVO {
    private Long messageId;
    private String role;
    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    private JsonNode charts;
    private JsonNode actionCards;
    private JsonNode sources;
    private JsonNode agentTraces;
    private JsonNode taskCard;
    private JsonNode workbench;
}
