package com.qiheng.erp.ai.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import org.mockito.ArgumentCaptor;

class ReportAgentsTest {
    @Test
    void reviewerReadsResultWithItsOwnTrustedIdentity() throws Exception {
        var results = mock(AnalysisResultTool.class);
        when(results.readAnalysisResult(eq("result_x"), any(ToolContext.class)))
                .thenReturn(Map.of("content", "库存 12 件"));
        var model = mock(ChatModel.class);
        var calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                var call = new AssistantMessage.ToolCall("call-2", "function", "readAnalysisResult", "{\"resultId\":\"result_x\"}");
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("读取分析结果").toolCalls(List.of(call)).build())));
            }
            Prompt prompt = invocation.getArgument(0);
            assertThat(prompt.getInstructions().toString()).contains("库存 12 件");
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"score\":9,\"strengths\":[\"数据准确\"],\"hasReferenceIssue\":false,\"issues\":[]}"))));
        });
        var agents = new ReportAgents(model, new ReportReadTools(mock(ReadDatasetTool.class), results),
                new ObjectMapper(), "撰写报告", "审核报告");
        var identity = new AgentToolContext(9, 10);
        assertThat(agents.review(Map.of("content", "库存 12 件"), identity).score()).isEqualTo(9);
        var captured = ArgumentCaptor.forClass(ToolContext.class);
        verify(results).readAnalysisResult(eq("result_x"), captured.capture());
        assertThat(captured.getValue().getContext().get(AgentToolContext.CONTEXT_KEY)).isEqualTo(identity);
    }

    @Test
    void writerReadsDatasetUsingTrustedContext() throws Exception {
        var datasets = mock(ReadDatasetTool.class);
        when(datasets.readDataset(eq("ds_x"), isNull(), isNull(), any(ToolContext.class)))
                .thenReturn(Map.of("rows", List.of(List.of(12))));
        var model = mock(ChatModel.class);
        var calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                var call = new AssistantMessage.ToolCall("call-1", "function", "readDataset", "{\"datasetId\":\"ds_x\"}");
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("先读取数据").toolCalls(List.of(call)).build())));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"decision\":\"REPORT\",\"title\":\"库存报告\",\"content\":\"库存 12 件。[1]\"}"))));
        });
        var agents = new ReportAgents(model, new ReportReadTools(datasets, mock(AnalysisResultTool.class)),
                new ObjectMapper(), "撰写报告", "审核报告");
        var identity = new AgentToolContext(1, 2);
        assertThat(agents.write(Map.of("datasetIds", List.of("ds_x")), identity)).contains("12");
        var captured = ArgumentCaptor.forClass(ToolContext.class);
        verify(datasets).readDataset(eq("ds_x"), isNull(), isNull(), captured.capture());
        assertThat(captured.getValue().getContext().get(AgentToolContext.CONTEXT_KEY)).isEqualTo(identity);
    }

    @Test
    void exposesOnlyReadToolsWithoutIdentityParameters() {
        var tools = new ReportReadTools(mock(ReadDatasetTool.class), mock(AnalysisResultTool.class));
        var callbacks = MethodToolCallbackProvider.builder().toolObjects(tools).build().getToolCallbacks();
        assertThat(callbacks).hasSize(2);
        for (var callback : callbacks) {
            assertThat(callback.getToolDefinition().name()).isIn("readDataset", "readAnalysisResult");
            assertThat(callback.getToolDefinition().inputSchema()).doesNotContain("userId", "conversationId", "context");
        }
    }

    @Test
    void reviewParsesFinalStructuredAnswer() throws Exception {
        var model = model("{\"score\":7,\"strengths\":[\"结构清晰\"],\"hasReferenceIssue\":false,\"issues\":[{\"problem\":\"数字不符\",\"reason\":\"来源不一致\",\"suggest\":\"重新核对\"}]}");
        var review = agents(model).review(Map.of("content", "报告"), new AgentToolContext(1, 2));
        assertThat(review.score()).isEqualTo(7);
        assertThat(review.issues()).hasSize(1);
        assertThat(review.issues().getFirst().suggestion()).isEqualTo("重新核对");
    }

    @Test
    void invalidReviewAndModelFailureNeverPass() {
        var identity = new AgentToolContext(1, 2);
        assertThatThrownBy(() -> agents(model("{\"score\":9,\"strengths\":[],\"issues\":[]}垃圾"))
                .review(Map.of("content", "报告"), identity)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> agents(model("{\"score\":7,\"strengths\":[],\"hasReferenceIssue\":false,\"issues\":[]}"))
                .review(Map.of("content", "报告"), identity)).hasMessageContaining("必须说明问题");
        assertThatThrownBy(() -> agents(model("Exception: 模型不可用"))
                .write(Map.of("userRequest", "库存"), identity)).isInstanceOf(Exception.class);
    }

    private static ReportAgents agents(ChatModel model) {
        return new ReportAgents(model, new ReportReadTools(mock(ReadDatasetTool.class), mock(AnalysisResultTool.class)),
                new ObjectMapper(), "撰写报告", "审核报告");
    }

    private static ChatModel model(String text) {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
        return model;
    }
}
