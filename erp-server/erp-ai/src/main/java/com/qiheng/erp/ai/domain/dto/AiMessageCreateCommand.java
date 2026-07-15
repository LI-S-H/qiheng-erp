package com.qiheng.erp.ai.domain.dto;

import lombok.Data;

/**
 * AI 模块内部写入消息的命令，不允许调用方指定用户 ID。
 */
@Data
public class AiMessageCreateCommand {
    private String role;
    private String content;
    private String chartsJson;
    private String actionCardsJson;
    private String sourcesJson;
    private String agentTracesJson;
    private String taskCardJson;
    private String workbenchJson;
}
