package com.qiheng.erp.ai.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.dto.GraphStartRequest;
import com.qiheng.erp.ai.service.GraphService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 验证 HTTP 边界以及全局忽略 null 配置下的必需响应字段。 */
class GraphControllerContractTest {

    private GraphService service;
    private MockMvc mvc;

    @BeforeEach
    void createController() {
        service = mock(GraphService.class);
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mvc = MockMvcBuilders.standaloneSetup(new GraphController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void blankAnswerCannotResumeGraphStream() throws Exception {
        mvc.perform(post("/ai/graph/resume/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"threadId\":\"thread\",\"pauseId\":\"pause\",\"answer\":\" \"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void streamEndpointPushesNodeProgressAsNamedSseEvents() throws Exception {
        // 事件体对象由消息转换器序列化，断言 data 行是裸 JSON 而不是被再包一层引号的字符串
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("node", "planner");
        progress.put("label", "正在规划");
        progress.put("durationMs", null);
        when(service.stream(any(GraphStartRequest.class))).thenReturn(Flux.just(
                ServerSentEvent.<Object>builder().event("node-start").data(progress).build()));

        MvcResult result = mvc.perform(post("/ai/graph/stream")
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversationId\":\"1\",\"query\":\"分析销量\"}"))
                .andExpect(request().asyncStarted()).andReturn();

        mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event:node-start")))
                .andExpect(dispatched -> {
                    String body = dispatched.getResponse().getContentAsString(StandardCharsets.UTF_8);
                    assertTrue(body.contains("data:{\"node\":\"planner\",\"label\":\"正在规划\"}"), "实际内容：" + body);
                });
    }
}
