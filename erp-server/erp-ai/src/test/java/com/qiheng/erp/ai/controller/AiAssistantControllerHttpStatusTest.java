package com.qiheng.erp.ai.controller;

import com.qiheng.erp.ai.domain.dto.AiChatRequest;
import com.qiheng.erp.ai.service.IAiAssistantService;
import com.qiheng.erp.ai.service.IAiConversationService;
import com.qiheng.erp.ai.service.IAiMessageService;
import com.qiheng.erp.common.dto.PageQuery;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.exception.ServiceUnavailableBizException;
import com.qiheng.erp.common.handler.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Map;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

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
                .setControllerAdvice(new GlobalExceptionHandler(
                        mock(com.qiheng.erp.common.mq.SystemExceptionMqPublisher.class)), new AiAssistantExceptionHandler())
                .build();
    }

    @Test
    void historyReturns404ForMissingOrForeignConversation() throws Exception {
        when(messageService.pageHistory(anyLong(), any(PageQuery.class)))
                .thenThrow(new BizException(ErrorCode.AI_CONVERSATION_NOT_FOUND));

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

    @Test
    void streamingReturnsNamedJsonEventsOverSse() throws Exception {
        SseEmitter emitter = new SseEmitter();
        emitter.send(SseEmitter.event().name("MESSAGE_DELTA").data(Map.of(
                "eventType", "MESSAGE_DELTA", "requestId", "req-1", "conversationId", "100", "contentDelta", "你好"),
                MediaType.APPLICATION_JSON));
        emitter.complete();
        when(assistantService.stream(any(AiChatRequest.class))).thenReturn(emitter);
        var result = mockMvc.perform(post("/ai/assistant/messages/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"100\",\"message\":\"你好\"}"))
                .andExpect(request().asyncStarted()).andReturn();
        mockMvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event:MESSAGE_DELTA")))
                .andExpect(result1 -> org.junit.jupiter.api.Assertions.assertTrue(
                        result1.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                                .contains("\"contentDelta\":\"你好\"")));
    }

    @Test
    void streamingUnavailableBeforeConnectionReturns503Json() throws Exception {
        when(assistantService.stream(any(AiChatRequest.class)))
                .thenThrow(new ServiceUnavailableBizException(ErrorCode.AI_SERVICE_UNAVAILABLE));
        mockMvc.perform(post("/ai/assistant/messages/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"100\",\"message\":\"你好\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ErrorCode.AI_SERVICE_UNAVAILABLE.getCode()));
    }

    @Test
    void bothProtocolsRejectMissingConversationId() throws Exception {
        for (String path : new String[]{"/messages", "/messages/stream"}) {
            mockMvc.perform(post("/ai/assistant" + path).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"message\":\"你好\"}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void sseMalformedBodyReturnsJson400() throws Exception {
        mockMvc.perform(post("/ai/assistant/messages/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }

    @Test
    void malformedConversationPathReturnsJson400() throws Exception {
        mockMvc.perform(get("/ai/assistant/conversations/not-long/messages"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ErrorCode.PARAM_ERROR.getCode()));
    }

    @Test
    void sseExpiredLoginAndPermissionErrorReturnJson() throws Exception {
        when(assistantService.stream(any(AiChatRequest.class)))
                .thenThrow(cn.dev33.satoken.exception.NotLoginException.newInstance("login",
                        cn.dev33.satoken.exception.NotLoginException.NOT_TOKEN, "未登录", null));
        mockMvc.perform(post("/ai/assistant/messages/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"100\",\"message\":\"你好\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
        when(assistantService.stream(any(AiChatRequest.class)))
                .thenThrow(new cn.dev33.satoken.exception.NotPermissionException("ai:assistant:chat"));
        mockMvc.perform(post("/ai/assistant/messages/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"100\",\"message\":\"你好\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON));
    }
}
