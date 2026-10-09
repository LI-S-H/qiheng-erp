package com.qiheng.erp.ai.service;

import com.qiheng.erp.ai.domain.dto.AiChatRequest;
import com.qiheng.erp.ai.domain.vo.AiChatResponseVO;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 智能经营助手业务编排接口。
 *
 * @author ERP-AI Team
 * @since 2026-10-08
 */
public interface IAiAssistantService {

    /**
     * 处理用户消息:组装上下文、调 Agent、写入 ai_message、返回最终回复。
     *
     * @param request 用户消息请求(必须带 conversationId)
     * @return 助手回复 + 会话 ID
     */
    AiChatResponseVO send(AiChatRequest request);

    SseEmitter stream(AiChatRequest request);
}
