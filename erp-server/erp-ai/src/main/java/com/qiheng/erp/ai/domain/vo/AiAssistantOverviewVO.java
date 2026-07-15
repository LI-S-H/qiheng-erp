package com.qiheng.erp.ai.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class AiAssistantOverviewVO {

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime refreshedAt;

    private List<Object> quickPrompts = new ArrayList<>();

    private List<AiConversationSummaryVO> conversations = new ArrayList<>();
}
