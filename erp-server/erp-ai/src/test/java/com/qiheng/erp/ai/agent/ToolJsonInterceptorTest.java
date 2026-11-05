package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.agent.interceptor.ToolJsonInterceptor;
import org.junit.jupiter.api.Test;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ToolJsonInterceptorTest {
    @Test
    void completeInterceptorChainReportsIdentityAndPreservesFailureWhenMqThrows() throws Exception {
        var model = mock(ChatModel.class);
        var calls = new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            var message = AssistantMessage.builder().toolCalls(List.of(new AssistantMessage.ToolCall(
                    "bad-" + calls.incrementAndGet(), "function", "query_sql_data", "{broken"))).build();
            return Flux.just(new ChatResponse(List.of(new Generation(message))));
        });
        var publisher = mock(com.qiheng.erp.common.mq.SystemExceptionMqPublisher.class);
        doThrow(new IllegalStateException("MQ failed")).when(publisher).publish(any());
        var events = new java.util.concurrent.CopyOnWriteArrayList<com.alibaba.cloud.ai.graph.NodeOutput>();
        var agent = ReactAgent.builder().name("tool-chain-test").model(model)
                .methodTools(new SqlQueryTool(mock(SqlGraphService.class)))
                .interceptors(new TrustedToolContextInterceptor(),
                        new com.qiheng.erp.ai.agent.interceptor.ToolRecordingInterceptor(),
                        new ToolJsonInterceptor(new ObjectMapper(), publisher)).build();
        var config = RunnableConfig.builder().threadId("tool-chain")
                .addMetadata("runId", "run-123")
                .addMetadata("trustedSqlContext", new com.qiheng.erp.ai.sql.tool.SqlToolContext(7, 123))
                .addMetadata("toolEvents", (java.util.function.Consumer<com.alibaba.cloud.ai.graph.NodeOutput>) events::add)
                .addMetadata(ToolJsonInterceptor.ERROR_COUNT, new AtomicInteger()).build();
        assertThatThrownBy(() -> agent.stream("查询", config).blockLast(Duration.ofSeconds(20)))
                .hasMessageContaining("工具参数多次格式错误");
        var reported = org.mockito.ArgumentCaptor.forClass(com.qiheng.erp.common.mq.SystemExceptionRecordMessage.class);
        verify(publisher).publish(reported.capture());
        assertThat(reported.getValue().getDetailSummary()).contains("runId=run-123", "conversationId=123", "bad-4");
        assertThat(events.stream().filter(event -> "tool_finished".equals(event.node()))
                .map(event -> event.state().data())
                .anyMatch(data -> "bad-4".equals(data.get("toolCallId"))
                        && "failed".equals(data.get("status")) && "参数错误，已停止".equals(data.get("label"))))
                .isTrue();
    }
    @Test
    void validJsonResetsConsecutiveErrors() throws Exception {
        var model = mock(ChatModel.class);
        var calls = new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            int index = calls.incrementAndGet();
            var message = index == 6 ? new AssistantMessage("完成") : AssistantMessage.builder().toolCalls(List.of(
                    new AssistantMessage.ToolCall("call-" + index, "function", "query_sql_data",
                            index == 3 ? "{}" : "{broken"))).build();
            return Flux.just(new ChatResponse(List.of(new Generation(message))));
        });
        var publisher = mock(com.qiheng.erp.common.mq.SystemExceptionMqPublisher.class);
        var accept = new com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor() {
            @Override public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
                return ToolCallResponse.of(request.getToolCallId(), request.getToolName(), "ok");
            }
            @Override public String getName() { return "accept"; }
        };
        var agent = ReactAgent.builder().name("reset-json-test").model(model)
                .methodTools(new SqlQueryTool(mock(SqlGraphService.class)))
                .interceptors(new ToolJsonInterceptor(new ObjectMapper(), publisher), accept).build();
        var counter = new AtomicInteger();
        var config = RunnableConfig.builder().threadId("reset-json")
                .addMetadata(ToolJsonInterceptor.ERROR_COUNT, counter).build();
        assertThat(agent.stream("查询", config).collectList().block(Duration.ofSeconds(20))).isNotEmpty();
        assertThat(counter).hasValue(2);
        verifyNoInteractions(publisher);
    }
    @Test
    void repeatedMalformedCallsStopAfterThreeCorrections() throws Exception {
        var model = mock(ChatModel.class);
        var publisher = mock(com.qiheng.erp.common.mq.SystemExceptionMqPublisher.class);
        var calls = new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            var message = AssistantMessage.builder().toolCalls(List.of(new AssistantMessage.ToolCall(
                    "bad-" + calls.incrementAndGet(), "function", "query_sql_data", "{broken"))).build();
            return Flux.just(new ChatResponse(List.of(new Generation(message))));
        });
        var agent = ReactAgent.builder().name("invalid-json-limit-test").model(model)
                .methodTools(new SqlQueryTool(mock(SqlGraphService.class)))
                .interceptors(new ToolJsonInterceptor(new ObjectMapper(), publisher)).build();
        var config = RunnableConfig.builder().threadId("invalid-json-limit")
                .addMetadata(ToolJsonInterceptor.ERROR_COUNT, new AtomicInteger()).build();
        assertThatThrownBy(() -> agent.stream("查询", config).blockLast(Duration.ofSeconds(20)))
                .hasMessageContaining("工具参数多次格式错误");
        assertThat(calls).hasValue(4);
        var reported = org.mockito.ArgumentCaptor.forClass(com.qiheng.erp.common.mq.SystemExceptionRecordMessage.class);
        verify(publisher).publish(reported.capture());
        assertThat(reported.getValue().getErrorCode()).isEqualTo("AI_TOOL_ARGUMENT_INVALID");
        assertThat(reported.getValue().getDetailSummary()).contains("query_sql_data", "bad-4");
    }
    @Test
    void frameworkReturnsMalformedToolCallAsMessageAndContinuesModel() throws Exception {
        var model = mock(ChatModel.class);
        var sawError = new AtomicBoolean();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            boolean returned = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                    .anyMatch(response -> response.id().equals("invalid-call") && response.name().equals("query_sql_data")
                            && response.responseData().contains("工具参数格式错误"));
            if (returned) {
                sawError.set(true);
            }
            var message = returned ? new AssistantMessage("已收到格式错误") : AssistantMessage.builder()
                    .toolCalls(List.of(new AssistantMessage.ToolCall("invalid-call", "function", "query_sql_data",
                            "{\"requirement\":{}, {\"requirement\":{}}}"))).build();
            return Flux.just(new ChatResponse(List.of(new Generation(message))));
        });
        var service = mock(SqlGraphService.class);
        var agent = ReactAgent.builder().name("invalid-json-test").model(model)
                .methodTools(new SqlQueryTool(service)).interceptors(new ToolJsonInterceptor(new ObjectMapper())).build();
        var output = agent.stream("查询", RunnableConfig.builder().threadId("invalid-json").build())
                .collectList().block(Duration.ofSeconds(20));
        assertThat(output).isNotEmpty();
        assertThat(sawError).isTrue();
        verifyNoInteractions(service);
    }
    @Test
    void invalidJsonBecomesToolResultWithoutExecutingTool() {
        var interceptor = new ToolJsonInterceptor(new ObjectMapper());
        var request = mock(ToolCallRequest.class);
        var handler = mock(ToolCallHandler.class);
        when(request.getToolName()).thenReturn("query_sql_data");
        when(request.getToolCallId()).thenReturn("call-1");
        when(request.getArguments()).thenReturn("{\"requirement\":{}, {\"requirement\":{}}}");
        var response = interceptor.interceptToolCall(request, handler);
        assertThat(response.getResult()).contains("工具参数格式错误");
        verifyNoInteractions(handler);
    }

    @Test
    void validJsonExecutesToolAndRuntimeErrorsAreNotHidden() {
        var interceptor = new ToolJsonInterceptor(new ObjectMapper());
        var request = mock(ToolCallRequest.class);
        var handler = mock(ToolCallHandler.class);
        when(request.getToolName()).thenReturn("query_sql_data");
        when(request.getArguments()).thenReturn("{\"requirement\":{}}");
        var expected = ToolCallResponse.of("call-1", "query_sql_data", "ok");
        when(handler.call(request)).thenReturn(expected);
        assertThat(interceptor.interceptToolCall(request, handler)).isSameAs(expected);
        verify(handler).call(request);
    }
}
