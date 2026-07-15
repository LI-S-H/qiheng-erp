package com.qiheng.erp.ai.service.impl;

import com.qiheng.erp.ai.domain.dto.AiChatRequest;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiAssistantServiceImplTest {

    @Test
    void sendShouldFailClearlyWhenRealAiIsNotEnabled() {
        AiAssistantServiceImpl service = new AiAssistantServiceImpl();
        AiChatRequest request = new AiChatRequest();
        request.setMessage("分析库存");

        BizException exception = assertThrows(BizException.class, () -> service.send(request));

        assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE.getCode(), exception.getCode());
    }
}
