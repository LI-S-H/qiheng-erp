package com.qiheng.erp.ai.controller;

import com.qiheng.erp.ai.domain.dto.AiChatRequest;
import com.qiheng.erp.ai.service.IAiAssistantService;
import com.qiheng.erp.ai.service.IAiConversationService;
import com.qiheng.erp.ai.service.IAiMessageService;
import com.qiheng.erp.common.dto.PageQuery;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.exception.NotFoundBizException;
import com.qiheng.erp.common.exception.ServiceUnavailableBizException;
import com.qiheng.erp.common.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AiAssistantControllerHttpStatusTest {

    private IAiMessageService messageService;
    private IAiAssistantService assistantService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        IAiConversationService conversationService = mock(IAiConversationService.class);
        messageService = mock(IAiMessageService.class);
        assistantService = mock(IAiAssistantService.class);
        AiAssistantController controller = new AiAssistantController(conversationService, messageService, assistantService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void historyReturns404ForMissingOrForeignConversation() throws Exception {
        when(messageService.pageHistory(anyLong(), any(PageQuery.class)))
                .thenThrow(new NotFoundBizException(ErrorCode.AI_CONVERSATION_NOT_FOUND));

        mockMvc.perform(get("/ai/assistant/conversations/100/messages")
                        .param("pageNum", "1")
                        .param("pageSize", "50"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.AI_CONVERSATION_NOT_FOUND.getCode()));
    }

    @Test
    void messagesReturns503WhenAiServiceIsUnavailable() throws Exception {
        when(assistantService.send(any(AiChatRequest.class)))
                .thenThrow(new ServiceUnavailableBizException(ErrorCode.AI_SERVICE_UNAVAILABLE));

        mockMvc.perform(post("/ai/assistant/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"100\",\"message\":\"分析库存\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.AI_SERVICE_UNAVAILABLE.getCode()));
    }
}
