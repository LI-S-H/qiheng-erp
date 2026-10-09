package com.qiheng.erp.ai.service;

import com.qiheng.erp.ai.domain.dto.AiConversationUpdateRequest;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.vo.AiAssistantOverviewVO;
import com.qiheng.erp.ai.domain.vo.AiConversationSummaryVO;

public interface IAiConversationService {
    AiAssistantOverviewVO overview();

    AiConversationSummaryVO create();

    AiConversationSummaryVO update(Long conversationId, AiConversationUpdateRequest request);

    void delete(Long conversationId);

    AiConversation getOwnedConversation(Long conversationId);

    /** 内部异步链路使用请求线程已认证的用户ID，不能从工作线程读取登录上下文。 */
    AiConversation getOwnedConversation(Long conversationId, Long userId);
}
