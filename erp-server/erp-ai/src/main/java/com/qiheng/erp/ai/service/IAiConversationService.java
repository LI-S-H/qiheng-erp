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
}
