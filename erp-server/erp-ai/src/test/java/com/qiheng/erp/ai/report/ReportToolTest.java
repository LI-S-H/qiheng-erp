package com.qiheng.erp.ai.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.report.graph.ReportService;
import com.qiheng.erp.ai.agent.TrustedToolContextInterceptor;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.*;

class ReportToolTest {
    @Test
    void schemaExposesStepDescriptionsWithoutTrustedContext() throws Exception {
        var tool = new ReportTool(mock(ReportService.class));
        var callback = MethodToolCallbackProvider.builder().toolObjects(tool).build().getToolCallbacks()[0];
        String schemaText = callback.getToolDefinition().inputSchema();
        var schema = new ObjectMapper().readTree(schemaText);
        assertThat(schema.path("properties").has("analysisSteps")).isTrue();
        assertThat(schemaText).contains("purpose", "action", "finding", "datasetIds", "resultIds", "分析目的");
        assertThat(schemaText).doesNotContain("userId", "conversationId", "runId", "context");
    }

    @Test
    void agentPassesTrustedRunIdAndDeserializesSteps() throws Exception {
        ReportService service = mock(ReportService.class);
        when(service.runForMainAgent(anyList(), any(), any(), anyString()))
                .thenReturn(Map.of("status", "COMPLETED", "report", "报告"));
        ChatModel model = mock(ChatModel.class);
        AtomicInteger count = new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            AssistantMessage message;
            if (count.incrementAndGet() == 1) {
                message = AssistantMessage.builder().toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "createReport",
                        "{\"analysisSteps\":[{\"step\":1,\"purpose\":\"了解\",\"action\":\"查询\","
                                + "\"finding\":\"10件\",\"datasetIds\":[\"ds_1\"],\"resultIds\":[]}]}"))).build();
            } else {
                message = new AssistantMessage("完成");
            }
            return Flux.just(new ChatResponse(List.of(new Generation(message))));
        });
        var agent = ReactAgent.builder().name("report-tool-context-test").model(model)
                .methodTools(new ReportTool(service))
                .interceptors(new TrustedToolContextInterceptor()).build();
        AgentToolContext identity = new AgentToolContext(7, 123);
        var config = RunnableConfig.builder().threadId("report-tool-context")
                .addMetadata(TrustedToolContextInterceptor.TRUSTED_CONTEXT, identity)
                .addMetadata("runId", "run-123").build();

        assertThat(agent.stream("生成报告", config).collectList().block(Duration.ofSeconds(20))).isNotEmpty();
        var steps = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(service).runForMainAgent(steps.capture(), isNull(), eq(identity), eq("run-123"));
        assertThat(steps.getValue().getFirst()).isInstanceOf(ReportAnalysisStep.class);
        assertThat(((ReportAnalysisStep) steps.getValue().getFirst()).finding()).isEqualTo("10件");
    }
}
