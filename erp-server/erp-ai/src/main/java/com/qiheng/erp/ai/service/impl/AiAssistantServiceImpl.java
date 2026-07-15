package com.qiheng.erp.ai.service.impl;

import com.qiheng.erp.ai.domain.dto.AiChatRequest;
import com.qiheng.erp.ai.domain.vo.AiChatResponseVO;
import com.qiheng.erp.ai.service.IAiAssistantService;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.exception.ServiceUnavailableBizException;
import org.springframework.stereotype.Service;

@Service
public class AiAssistantServiceImpl implements IAiAssistantService {

    @Override
    public AiChatResponseVO send(AiChatRequest request) {
        // 未接入真实模型和受控业务 Tool 前，禁止构造固定回复冒充 AI 结果。
        throw new ServiceUnavailableBizException(ErrorCode.AI_SERVICE_UNAVAILABLE);
    }
}
