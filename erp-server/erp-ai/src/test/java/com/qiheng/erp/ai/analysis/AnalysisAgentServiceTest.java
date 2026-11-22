package com.qiheng.erp.ai.analysis;

import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.python.tool.PythonAnalysisTool;
import com.qiheng.erp.ai.python.sandbox.PythonSandboxRuntime;
import org.springframework.ai.chat.model.ToolContext;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.agent.interceptor.ToolJsonInterceptor;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetPage;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.dataset.tool.ListDatasetsTool;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;

class AnalysisAgentServiceTest {
    /** 模型返回失败文本时给出可读说明，并把原始原因记入流水。 */
    @Test
    void modelFailureProducesReadableSummaryAndTrace() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("Exception: 上游超时")))));
        var traces = mock(AiAgentTraceMapper.class);
        var mapper = new ObjectMapper();
        var service = new AnalysisAgentService(model, mock(PythonAnalysisTool.class),
                mock(PythonSandboxRuntime.class), "分析", traces, mapper, null, null,
                new ToolJsonInterceptor(mapper), null, mock(AnalysisResultTool.class), null, null, null, null, null);

        String summary = service.analyze("分析库存", new AgentToolContext(10, 20));

        assertThat(summary).contains("分析未完成：模型调用失败", "原始原因：上游超时", "建议：");
        assertThat(summary).doesNotContain("Exception:");
        verify(traces).insert(eq(10L), eq(20L), anyString(), eq("ANALYSIS"), eq("MODEL_FAILURE"),
                argThat(content -> content.contains("Exception: 上游超时")));
        verify(traces, never()).insert(eq(10L), eq(20L), anyString(), eq("ANALYSIS"), eq("FINAL"), anyString());
    }

    /** 多轮分析应每次收到一份图谱和当前任务的业务日期。 */
    @Test
    void injectsBusinessGraphAndDateAcrossModelCalls() throws Exception {
        var graph = mock(com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider.class);
        String marker = "【ERP 业务图谱】仓库关联库存";
        when(graph.render()).thenReturn(marker);
        var model = mock(ChatModel.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String text = prompt.getInstructions().toString();
            assertThat(text).contains("分析", marker, "当前业务日期：2026-11-05");
            assertThat(text.indexOf(marker)).isEqualTo(text.lastIndexOf(marker));
            var message = calls.getAndIncrement() == 0
                    ? AssistantMessage.builder().toolCalls(List.of(new AssistantMessage.ToolCall(
                            "sql", "function", "query_sql_data", "{"))).build()
                    : new AssistantMessage(handoff("完成"));
            return new ChatResponse(List.of(new Generation(message)));
        });
        var mapper = new ObjectMapper();
        var service = new AnalysisAgentService(model, mock(PythonAnalysisTool.class), mock(PythonSandboxRuntime.class),
                "分析", mock(AiAgentTraceMapper.class), mapper, new SqlQueryTool(mock(SqlGraphService.class)),
                new ReadDatasetTool(mock(DatasetManager.class)), new ToolJsonInterceptor(mapper), mock(ListDatasetsTool.class),
                mock(AnalysisResultTool.class), null, null, null, null, null,
                new com.qiheng.erp.ai.agent.interceptor.BusinessGraphInterceptor(graph));

        assertThat(service.analyze("分析库存", new AgentToolContext(10, 20, "2026-11-05"))).isEqualTo("完成");
        assertThat(calls).hasValue(2);
        verify(graph, times(2)).render();
    }

    /** 连续四次格式错误应停止当前任务，不能反复调用 SQL。 */
    @Test
    void stopsAfterRepeatedInvalidSqlArguments() throws Exception {
        var model = mock(ChatModel.class);
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            var call = new AssistantMessage.ToolCall("call-" + counter.getAndIncrement(),
                    "function", "query_sql_data", "{broken");
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call)).build())));
        });
        var runtime = mock(PythonSandboxRuntime.class);
        var python = mock(PythonAnalysisTool.class);
        var identity = new AgentToolContext(10, 20);
        var sqlService = mock(SqlGraphService.class);
        var traces = mock(AiAgentTraceMapper.class);
        var mapper = new ObjectMapper();
        var service = new AnalysisAgentService(model, python, runtime, "分析", traces, mapper,
                new SqlQueryTool(sqlService), new ReadDatasetTool(mock(DatasetManager.class)), new ToolJsonInterceptor(mapper),
                mock(ListDatasetsTool.class), mock(AnalysisResultTool.class), null, null, null, null, null);

        assertThatThrownBy(() -> service.analyze("分析", identity)).isNotNull();
        assertThat(counter.get()).isEqualTo(4);
        verify(sqlService, never()).startForUser(anyLong(), anyLong(), anyString(), anyMap());
        verify(traces).insert(eq(10L), eq(20L), anyString(), eq("ANALYSIS"), eq("ERROR"), anyString());
        verify(runtime, never()).openSession(anyString());
    }

    /** 连续格式错误后若出现有效参数，应清空计数并允许后续错误重新计数。 */
    @Test
    void resetsJsonFailureCountAfterValidCall() throws Exception {
        var model = mock(ChatModel.class);
        var counter = new java.util.concurrent.atomic.AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            int turn = counter.getAndIncrement();
            if (turn == 7) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage(handoff("完成")))));
            }
            String arguments = turn == 3
                    ? "{\"requirement\":{\"goal\":\"查询库存\",\"dataRequirement\":\"FULL\"}}"
                    : "{broken";
            var call = new AssistantMessage.ToolCall("call-" + turn, "function", "query_sql_data", arguments);
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call)).build())));
        });
        var runtime = mock(PythonSandboxRuntime.class);
        var python = mock(PythonAnalysisTool.class);
        var identity = new AgentToolContext(10, 20);
        var sqlService = mock(SqlGraphService.class);
        when(sqlService.startForUser(eq(10L), eq(20L), anyString(), anyMap()))
                .thenReturn(Map.of("status", "NEED_INFO", "sqlRunId", "run-1"));
        var mapper = new ObjectMapper();
        var service = new AnalysisAgentService(model, python, runtime, "分析", mock(AiAgentTraceMapper.class), mapper,
                new SqlQueryTool(sqlService), new ReadDatasetTool(mock(DatasetManager.class)), new ToolJsonInterceptor(mapper),
                mock(ListDatasetsTool.class), mock(AnalysisResultTool.class), null, null, null, null, null);

        assertThat(service.analyze("分析", identity)).isEqualTo("完成");
        verify(sqlService, times(1)).startForUser(eq(10L), eq(20L), anyString(), anyMap());
        verify(runtime, never()).openSession();
    }

    /** 取数权限拒绝和缺少条件时，原始工具反馈必须交还模型。 */
    @Test
    void returnsSqlFailureOrMissingInformationToModel() throws Exception {
        for (var outcome : List.of("FAILED", "NEED_INFO")) {
            var model = mock(ChatModel.class);
            var call = new AssistantMessage.ToolCall("sql", "function", "query_sql_data",
                    "{\"requirement\":{\"goal\":\"查询库存\",\"dataRequirement\":\"FULL\"}}");
            var seen = new java.util.concurrent.atomic.AtomicReference<String>();
            when(model.call(any(Prompt.class))).thenReturn(
                    new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call)).build()))))
                    .thenAnswer(invocation -> {
                        Prompt prompt = invocation.getArgument(0);
                        prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                                .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream())
                                .findFirst().ifPresent(item -> seen.set(item.responseData()));
                        return new ChatResponse(List.of(new Generation(new AssistantMessage(handoff("说明无法完成")))));
                    });
            var runtime = mock(PythonSandboxRuntime.class);
            var python = mock(PythonAnalysisTool.class);
            var identity = new AgentToolContext(10, 20);
            var sqlService = mock(SqlGraphService.class);
            when(sqlService.startForUser(eq(10L), eq(20L), anyString(), anyMap()))
                    .thenReturn(Map.of("status", outcome, "sqlRunId", "run-1", "message", "缺少条件或权限"));
            var mapper = new ObjectMapper();
            var service = new AnalysisAgentService(model, python, runtime, "分析", mock(AiAgentTraceMapper.class), mapper,
                    new SqlQueryTool(sqlService), new ReadDatasetTool(mock(DatasetManager.class)), new ToolJsonInterceptor(mapper),
                mock(ListDatasetsTool.class), mock(AnalysisResultTool.class), null, null, null, null, null);

            assertThat(service.analyze("分析", identity)).isEqualTo("说明无法完成");
            assertThat(seen.get()).contains(outcome, "缺少条件或权限");
            verify(sqlService, times(1)).startForUser(eq(10L), eq(20L), anyString(), anyMap());
        }
    }

    /** SQL 和数据集工具必须使用服务端身份，不能由模型提供用户 ID。 */
    @Test
    void passesTrustedIdentityToSqlAndDatasetTools() throws Exception {
        var model = mock(ChatModel.class);
        var calls = List.of(new AssistantMessage.ToolCall("sql", "function", "query_sql_data",
                        "{\"requirement\":{\"goal\":\"查询一号仓库存\",\"dataRequirement\":\"FULL\"}}"),
                new AssistantMessage.ToolCall("read", "function", "readDataset", "{\"datasetId\":\"ds-1\"}"));
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(calls).build()))),
                new ChatResponse(List.of(new Generation(new AssistantMessage(handoff("完成"))))));
        var runtime = mock(PythonSandboxRuntime.class);
        var python = mock(PythonAnalysisTool.class);
        var identity = new AgentToolContext(10, 20, "2026-11-08");
        var sqlService = mock(SqlGraphService.class);
        when(sqlService.startForUserAtDate(eq(10L), eq(20L), anyString(), anyMap(), eq("2026-11-08")))
                .thenReturn(Map.of("status", "SUCCESS", "sqlRunId", "run-1"));
        var manager = mock(DatasetManager.class);
        when(manager.readPage("ds-1", 10, 20, 0, Integer.MAX_VALUE))
                .thenReturn(new DatasetPage(List.of(), List.of(), 0, 0, false));
        var mapper = new ObjectMapper();
        var service = new AnalysisAgentService(model, python, runtime, "分析", mock(AiAgentTraceMapper.class), mapper,
                new SqlQueryTool(sqlService), new ReadDatasetTool(manager), new ToolJsonInterceptor(mapper),
                mock(ListDatasetsTool.class), mock(AnalysisResultTool.class), null, null, null, null, null);

        assertThat(service.analyze("分析", identity)).isEqualTo("完成");
        verify(sqlService).startForUserAtDate(eq(10L), eq(20L), anyString(), anyMap(), eq("2026-11-08"));
        verify(manager).readPage("ds-1", 10, 20, 0, Integer.MAX_VALUE);
    }

    /** 流水存储不可用时仍交付分析结果并释放沙箱。 */
    @Test
    void continuesWhenTraceStoreFails() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(handoff("分析完成"))))));
        var runtime = mock(PythonSandboxRuntime.class);
        var tool = mock(PythonAnalysisTool.class);
        var identity = new AgentToolContext(10, 20);
        var traces = mock(AiAgentTraceMapper.class);
        doThrow(new IllegalStateException("数据库不可用")).when(traces)
                .insert(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString());
        var service = new AnalysisAgentService(model, tool, runtime, "分析", traces, new ObjectMapper(), null, null,
                new ToolJsonInterceptor(new ObjectMapper()), null, mock(AnalysisResultTool.class), null, null, null, null, null);

        assertThat(service.analyze("计算", identity)).isEqualTo("分析完成");
        
    }

    /** 工具失败后再次调用时，失败与修正结果都要保留。 */
    @Test
    void recordsFailedToolAndRetry() throws Exception {
        var model = mock(ChatModel.class);
        var first = new AssistantMessage.ToolCall("first", "function", "executePython",
                "{\"code\":\"bad\",\"datasetIds\":[],\"saveResult\":false}");
        var second = new AssistantMessage.ToolCall("second", "function", "executePython",
                "{\"code\":\"fixed\",\"datasetIds\":[],\"saveResult\":false}");
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(first)).build()))),
                new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(second)).build()))),
                new ChatResponse(List.of(new Generation(new AssistantMessage(handoff("完成"))))));
        var runtime = mock(PythonSandboxRuntime.class);
        var tool = mock(PythonAnalysisTool.class);
        when(tool.executePython(anyString(), anyList(), anyBoolean(), nullable(String.class), nullable(Integer.class),  any(ToolContext.class)))
                .thenReturn(Map.of("status", "FAILED", "message", "代码错误"), Map.of("status", "SUCCESS"));
        var identity = new AgentToolContext(10, 20);
        var traces = mock(AiAgentTraceMapper.class);
        var service = new AnalysisAgentService(model, tool, runtime, "分析", traces, new ObjectMapper(), null, null,
                new ToolJsonInterceptor(new ObjectMapper()), null, mock(AnalysisResultTool.class), null, null, null, null, null);

        assertThat(service.analyze("计算", identity)).isEqualTo("完成");
        verify(traces).insert(eq(10L), eq(20L), anyString(), eq("ANALYSIS"), eq("TOOL_RESULT"), contains("FAILED"));
        verify(traces).insert(eq(10L), eq(20L), anyString(), eq("ANALYSIS"), eq("TOOL_RESULT"), contains("SUCCESS"));
        verify(traces, times(2)).insert(eq(10L), eq(20L), anyString(), eq("ANALYSIS"), eq("TOOL_CALL"), anyString());
    }

    /** 完整同步链路中，文字、多个工具调用和结果都必须记录。 */
    @Test
    void recordsActualAgentMessagesAndUsesOneTaskId() throws Exception {
        var model = mock(ChatModel.class);
        var calls = List.of(new AssistantMessage.ToolCall("a", "function", "prepareAnalysisData", "{\"datasetIds\":[]}"),
                new AssistantMessage.ToolCall("b", "function", "executePython", "{\"code\":\"print(1)\",\"datasetIds\":[],\"saveResult\":false}"));
        var response = AssistantMessage.builder().content("先准备数据").toolCalls(calls).build();
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(response))),
                new ChatResponse(List.of(new Generation(new AssistantMessage(handoff("分析完成"))))));
        var runtime = mock(PythonSandboxRuntime.class);
        var tool = mock(PythonAnalysisTool.class);
        when(tool.prepareAnalysisData(anyList(), any(ToolContext.class))).thenReturn(Map.of("status", "SUCCESS"));
        when(tool.executePython(anyString(), anyList(), anyBoolean(), nullable(String.class), nullable(Integer.class),  any(ToolContext.class))).thenReturn(Map.of("status", "SUCCESS"));
        var identity = new AgentToolContext(10, 20);
        var traces = mock(AiAgentTraceMapper.class);
        var service = new AnalysisAgentService(model, tool, runtime, "你是分析助手。", traces, new ObjectMapper(), null, null,
                new ToolJsonInterceptor(new ObjectMapper()), null, mock(AnalysisResultTool.class), null, null, null, null, null);

        assertThat(service.analyze("分析数据", identity)).isEqualTo("分析完成");
        var task = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(traces, atLeastOnce()).insert(eq(10L), eq(20L), task.capture(), eq("ANALYSIS"), eq("INPUT"), anyString());
        var events = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(traces, times(7)).insert(eq(10L), eq(20L), eq(task.getValue()), eq("ANALYSIS"), events.capture(), anyString());
        assertThat(events.getAllValues()).containsExactly("INPUT", "MODEL_OUTPUT", "TOOL_CALL", "TOOL_CALL",
                "TOOL_RESULT", "TOOL_RESULT", "FINAL");
        
    }
    /** 模型异常时仍应关闭任务工具，避免沙箱一直占用资源。 */
    @Test
    void closesTaskToolWhenModelFails() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("模型不可用"));
        var runtime = mock(PythonSandboxRuntime.class);
        var tool = mock(PythonAnalysisTool.class);
        var identity = new AgentToolContext(10, 20);
        var traces = mock(AiAgentTraceMapper.class);
        var service = new AnalysisAgentService(model, tool, runtime, "你是分析助手。", traces, new ObjectMapper(), null, null,
                new ToolJsonInterceptor(new ObjectMapper()), null, mock(AnalysisResultTool.class), null, null, null, null, null);

        assertThatThrownBy(() -> service.analyze("分析数据", identity)).isNotNull();
        
        verify(runtime, never()).openSession();
        verify(traces).insert(eq(10L), eq(20L), anyString(), eq("ANALYSIS"), eq("ERROR"), anyString());
        verify(traces, never()).insert(anyLong(), anyLong(), anyString(), anyString(), eq("FINAL"), anyString());
    }

    /** 模型在 Python 调用后失败时，已打开的任务沙箱仍需关闭。 */
    @Test
    void closesOpenedSandboxWhenModelFails() {
        var model = mock(ChatModel.class);
        var call = new AssistantMessage.ToolCall("python", "function", "executePython",
                "{\"code\":\"print(1)\",\"datasetIds\":[],\"saveResult\":false}");
        when(model.call(any(Prompt.class)))
                .thenReturn(new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call)).build()))))
                .thenThrow(new IllegalStateException("模型不可用"));
        var runtime = mock(PythonSandboxRuntime.class);
        var sandbox = mock(com.qiheng.erp.ai.python.sandbox.PythonSandboxSession.class);
        when(runtime.openSession(anyString())).thenReturn(sandbox);
        when(sandbox.execute(anyString(), anyInt(), anyInt())).thenReturn(
                new com.qiheng.erp.ai.python.sandbox.PythonExecutionResult(true, "1", "", 1, false, "output", null));
        var python = new PythonAnalysisTool(mock(com.qiheng.erp.ai.python.tool.PythonDatasetPreparer.class),
                mock(com.qiheng.erp.ai.python.result.AnalysisResultStore.class));
        var mapper = new ObjectMapper();
        var service = new AnalysisAgentService(model, python, runtime, "分析", mock(AiAgentTraceMapper.class), mapper,
                null, null, new ToolJsonInterceptor(mapper), null, mock(AnalysisResultTool.class), null, null, null, null, null);
        assertThatThrownBy(() -> service.analyze("计算", new AgentToolContext(10, 20))).isNotNull();
        verify(runtime).openSession(anyString());
        verify(sandbox).close();
    }

    /** 共享 Agent 并发处理两个会话时，可信身份与流水任务不能串线。 */
    @Test
    void isolatesConcurrentTasksAndTraces() throws Exception {
        var model = mock(ChatModel.class);
        var rendezvous = new java.util.concurrent.CyclicBarrier(2);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            boolean hasResult = prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
            if (hasResult) {
                String response = prompt.getInstructions().stream()
                        .filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast)
                        .flatMap(message -> message.getResponses().stream())
                        .map(ToolResponseMessage.ToolResponse::responseData)
                        .findFirst().orElseThrow();
                return new ChatResponse(List.of(new Generation(new AssistantMessage(handoff(response.contains("owner-10") ? "完成-10" : "完成-11")))));
            }
            rendezvous.await(10, java.util.concurrent.TimeUnit.SECONDS);
            var call = new AssistantMessage.ToolCall(java.util.UUID.randomUUID().toString(), "function", "query_sql_data",
                    "{\"requirement\":{\"goal\":\"查询库存\",\"dataRequirement\":\"FULL\"}}");
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call)).build())));
        });
        var sqlService = mock(SqlGraphService.class);
        when(sqlService.startForUser(anyLong(), anyLong(), anyString(), anyMap())).thenAnswer(invocation ->
                Map.of("status", "FAILED", "message", "owner-" + invocation.getArgument(0)));
        var traces = mock(AiAgentTraceMapper.class);
        var mapper = new ObjectMapper();
        var runtime = mock(PythonSandboxRuntime.class);
        var service = new AnalysisAgentService(model, mock(PythonAnalysisTool.class), runtime, "分析", traces, mapper,
                new SqlQueryTool(sqlService), new ReadDatasetTool(mock(DatasetManager.class)),
                new ToolJsonInterceptor(mapper), mock(ListDatasetsTool.class), mock(AnalysisResultTool.class), null, null, null, null, null);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.analyze("任务 10", new AgentToolContext(10, 20)));
            var second = pool.submit(() -> service.analyze("任务 11", new AgentToolContext(11, 21)));
            assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("完成-10");
            assertThat(second.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("完成-11");
            verify(sqlService).startForUser(eq(10L), eq(20L), anyString(), anyMap());
            verify(sqlService).startForUser(eq(11L), eq(21L), anyString(), anyMap());
            var firstId = org.mockito.ArgumentCaptor.forClass(String.class);
            var secondId = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(traces).insert(eq(10L), eq(20L), firstId.capture(), eq("ANALYSIS"), eq("INPUT"), anyString());
            verify(traces).insert(eq(11L), eq(21L), secondId.capture(), eq("ANALYSIS"), eq("INPUT"), anyString());
            assertThat(firstId.getValue()).isNotEqualTo(secondId.getValue());
            verify(traces).insert(eq(10L), eq(20L), eq(firstId.getValue()), eq("ANALYSIS"), eq("TOOL_RESULT"), contains("owner-10"));
            verify(traces).insert(eq(11L), eq(21L), eq(secondId.getValue()), eq("ANALYSIS"), eq("TOOL_RESULT"), contains("owner-11"));
            verify(runtime, never()).openSession();
        } finally {
            pool.shutdownNow();
        }
    }
    /** 模拟分析模型按交接格式给出结论。 */
    private static String handoff(String summary) {
        return "{\"completion\":\"COMPLETED\",\"summary\":\"" + summary + "\"}";
    }
}
