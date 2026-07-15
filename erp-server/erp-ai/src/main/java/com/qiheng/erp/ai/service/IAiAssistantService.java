package com.qiheng.erp.ai.service;

import com.qiheng.erp.ai.domain.dto.AiChatRequest;
import com.qiheng.erp.ai.domain.vo.AiChatResponseVO;

public interface IAiAssistantService {
    AiChatResponseVO send(AiChatRequest request);
}
