package com.qiheng.erp.ai.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import org.mockito.ArgumentCaptor;

class ReportAgentsTest {
    /** 同一会话两次调用即使复用了模型调用 ID，任务流水和上下文仍相互隔离。 */
    @Test
    void repeatedToolCallIdCreatesIndependentTaskAndProgress() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var received = new ArrayList<Map<String, Object>>();
        var first = new ReportProgress("same-call", (event, data) -> received.add(data));
        var second = new ReportProgress("same-call", (event, data) -> received.add(data));
        assertThat(UUID.fromString(first.taskId())).isNotNull();
        assertThat(first.taskId()).isNotEqualTo(second.taskId());
        var model = mock(ChatModel.class);
        var calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            if (calls.getAndIncrement() == 1) {
                assertThat(prompt.getInstructions().toString()).contains("第二轮材料").doesNotContain("第一轮材料");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "{\"decision\":\"REPORT\",\"title\":\"报告\",\"content\":\"完成\"}"))));
        });
        var agents = new ReportAgents(model, new ReportReadTools(mock(ReadDatasetTool.class), mock(AnalysisResultTool.class)),
                new ObjectMapper(), "撰写报告", "审核报告", traces, null);
        var identity = new AgentToolContext(1, 2);
        try (var firstTask = agents.openTask(identity, first); var secondTask = agents.openTask(identity, second)) {
            first.stage("PREPARING", "准备第一轮");
            firstTask.write("第一轮材料", null);
            second.stage("PREPARING", "准备第二轮");
            secondTask.write("第二轮材料", null);
            first.ready(Map.of("reportId", "rpt_1"));
        }
        verify(traces).insert(eq(1L), eq(2L), eq(first.taskId()), eq("REPORT_WRITER"), eq("INPUT"), any());
        verify(traces).insert(eq(1L), eq(2L), eq(second.taskId()), eq("REPORT_WRITER"), eq("INPUT"), any());
        assertThat(received).extracting(data -> data.get("taskId"))
                .containsExactly(first.taskId(), second.taskId(), first.taskId());
        assertThat(received).allSatisfy(data -> assertThat(data.get("toolCallId")).isEqualTo("same-call"));
    }

    @Test
    void recordsWriterRepairAndReviewerOutputUnderReportTask() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var model = mock(ChatModel.class);
        var calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            int call = calls.getAndIncrement();
            String answer = switch (call) {
                case 0 -> "格式错误";
                case 1 -> "{\"decision\":\"REPORT\",\"title\":\"库存报告\",\"content\":\"库存 12 件\"}";
                default -> "{\"score\":9,\"strengths\":[\"清楚\"],\"hasReferenceIssue\":false,\"issues\":[]}";
            };
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        });
        var agents = new ReportAgents(model, new ReportReadTools(mock(ReadDatasetTool.class), mock(AnalysisResultTool.class)),
                new ObjectMapper(), "撰写报告", "审核报告", traces, null);
        var progress = new ReportProgress("call-report-1", (event, data) -> {});
        try (var task = agents.openTask(new AgentToolContext(1, 2), progress)) {
            task.write("分析材料", null);
            task.review("分析材料", "库存 12 件", null);
        }
        verify(traces).insert(eq(1L), eq(2L), eq(progress.taskId()), eq("REPORT_WRITER"), eq("INPUT"), any());
        var output = ArgumentCaptor.forClass(String.class);
        verify(traces, times(2)).insert(eq(1L), eq(2L), eq(progress.taskId()), eq("REPORT_WRITER"), eq("MODEL_OUTPUT"), output.capture());
        assertThat(output.getAllValues()).anyMatch(value -> value.contains("格式错误"))
                .anyMatch(value -> value.contains("库存报告"));
        verify(traces).insert(eq(1L), eq(2L), eq(progress.taskId()), eq("REPORT_WRITER"), eq("FORMAT_FEEDBACK"), any());
        verify(traces).insert(eq(1L), eq(2L), eq(progress.taskId()), eq("REPORT_REVIEWER"), eq("MODEL_OUTPUT"), any());
        assertThat(calls).hasValue(3);
    }

    @Test
    void recordsToolRequestAndResultWithTheSameCallId() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var datasets = mock(ReadDatasetTool.class);
        when(datasets.readDataset(eq("ds_x"), isNull(), isNull(), any(ToolContext.class)))
                .thenReturn(Map.of("rows", List.of(List.of(12))));
        var model = mock(ChatModel.class);
        var calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                var call = new AssistantMessage.ToolCall("read-call", "function", "readDataset", "{\"datasetId\":\"ds_x\"}");
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("读取数据")
                        .toolCalls(List.of(call)).build())));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "{\"decision\":\"REPORT\",\"title\":\"库存报告\",\"content\":\"库存 12 件\"}"))));
        });
        var agents = new ReportAgents(model, new ReportReadTools(datasets, mock(AnalysisResultTool.class)),
                new ObjectMapper(), "撰写报告", "审核报告", traces, null);
        var progress = new ReportProgress("report-call", (event, data) -> {});
        try (var task = agents.openTask(new AgentToolContext(1, 2), progress)) {
            task.write("分析库存", null);
        }
        var call = ArgumentCaptor.forClass(String.class);
        var result = ArgumentCaptor.forClass(String.class);
        verify(traces).insert(eq(1L), eq(2L), eq(progress.taskId()), eq("REPORT_WRITER"), eq("TOOL_CALL"), call.capture());
        verify(traces).insert(eq(1L), eq(2L), eq(progress.taskId()), eq("REPORT_WRITER"), eq("TOOL_RESULT"), result.capture());
        assertThat(call.getValue()).contains("read-call", "ds_x");
        assertThat(result.getValue()).contains("read-call", "12");
    }

    @Test
    void sameToolCallIdAcrossConversationsKeepsSeparateExpertContext() throws Exception {
        var model = mock(ChatModel.class);
        var calls = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            if (calls.getAndIncrement() == 1) {
                assertThat(prompt.getInstructions().toString()).contains("第二个会话的材料")
                        .doesNotContain("第一个会话的材料");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "{\"decision\":\"REPORT\",\"title\":\"报告\",\"content\":\"完成\"}"))));
        });
        var agents = agents(model);
        var progress = new ReportProgress("same-call", (event, data) -> {});
        try (var first = agents.openTask(new AgentToolContext(1, 10), progress);
             var second = agents.openTask(new AgentToolContext(1, 20), progress)) {
            first.write("第一个会话的材料", null);
            second.write("第二个会话的材料", null);
        }
        assertThat(calls).hasValue(2);
    }
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
                new ObjectMapper(), "撰写报告", "审核报告", null, null);
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
                new ObjectMapper(), "撰写报告", "审核报告", null, null);
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
                new ObjectMapper(), "撰写报告", "审核报告", null, null);
    }

    private static ChatModel model(String text) {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
        return model;
    }
}
