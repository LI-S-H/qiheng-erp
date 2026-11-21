package com.qiheng.erp.ai.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.agent.interceptor.ToolJsonInterceptor;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetPage;
import com.qiheng.erp.ai.dataset.DatasetRecord;
import com.qiheng.erp.ai.dataset.tool.ListDatasetsTool;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.domain.entity.AiAnalysisResult;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import com.qiheng.erp.ai.python.sandbox.PythonSandboxRuntime;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import com.qiheng.erp.ai.python.tool.PythonAnalysisTool;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;

class AnalysisAgentHandoffTest {
    /** 实际服务在原任务中修正最终格式，并保留正常工具流水。 */
    @Test
    void repairsFinalOutputWithoutRepeatingTool() throws Exception {
        var model = mock(ChatModel.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var seen = new AtomicReference<String>();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            int turn = calls.getAndIncrement();
            if (turn == 0) {
                var call = new AssistantMessage.ToolCall("sql-1", "function", "query_sql_data",
                        "{\"requirement\":{\"goal\":\"查询库存\",\"dataRequirement\":\"FULL\"}}");
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("先查询库存").toolCalls(List.of(call)).build())));
            }
            if (turn == 1) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("不是 JSON"))));
            }
            Prompt prompt = invocation.getArgument(0);
            seen.set(prompt.getInstructions().toString());
            return new ChatResponse(List.of(new Generation(new AssistantMessage(
                    "{\"completion\":\"NEED_INFO\",\"summary\":\"需要仓库编号\"}"))));
        });
        var identity = new AgentToolContext(10, 20);
        var datasets = mock(DatasetManager.class);
        when(datasets.listReady(10, 20)).thenReturn(List.of());
        var sqlService = mock(SqlGraphService.class);
        when(sqlService.startForUser(eq(10L), eq(20L), anyString(), anyMap()))
                .thenReturn(Map.of("status", "NEED_INFO", "message", "需要仓库编号"));
        var traces = mock(AiAgentTraceMapper.class);
        var mapper = new ObjectMapper();
        var store = mock(AnalysisResultStore.class);
        var service = new AnalysisAgentService(model, mock(PythonAnalysisTool.class),
                mock(PythonSandboxRuntime.class), "分析", traces, mapper, new SqlQueryTool(sqlService),
                new ReadDatasetTool(datasets), new ToolJsonInterceptor(mapper), new ListDatasetsTool(datasets),
                new AnalysisResultTool(store), datasets, store, null, null, null);

        var result = service.analyzeForMainAgent(new AnalysisCommand("分析库存", null, identity, null));
        assertThat(result.completion()).isEqualTo("NEED_INFO");
        assertThat(result.summary()).contains("仓库编号");
        assertThat(seen.get()).contains("不是 JSON", "需要仓库编号", "JSON 格式错误");
        assertThat(calls).hasValue(3);
        verify(sqlService, times(1)).startForUser(eq(10L), eq(20L), anyString(), anyMap());
        verify(traces, times(1)).insert(eq(10L), eq(20L), anyString(), eq("ANALYSIS"), eq("TOOL_CALL"), anyString());
        verify(traces, times(1)).insert(eq(10L), eq(20L), anyString(), eq("ANALYSIS"), eq("TOOL_RESULT"), anyString());
    }

    /** 三次修正后仍格式错误，应停止并保留已保存结果。 */
    @Test
    void rejectsInvalidHandoffAndKeepsSavedFiles() throws Exception {
        for (String output : List.of("不是 JSON", "{}", "{\"completion\":\"COMPLETED\",\"summary\":\"完成\"},",
                "{\"completion\":\"UNKNOWN\",\"summary\":\"完成\"}",
                "{\"completion\":\"COMPLETED\",\"summary\":\" \"}")) {
            var model = mock(ChatModel.class);
            when(model.call(any(Prompt.class))).thenReturn(
                    new ChatResponse(List.of(new Generation(new AssistantMessage(output)))));
            var identity = new AgentToolContext(10, 20);
            var datasets = mock(DatasetManager.class);
            when(datasets.listReady(10, 20)).thenReturn(List.of());
            var saved = new AiAnalysisResult();
            saved.setResultId("saved-before-error");
            var store = mock(AnalysisResultStore.class);
            when(store.list(eq(identity), anyString())).thenReturn(List.of(saved));
            var mapper = new ObjectMapper();
            var service = new AnalysisAgentService(model, mock(PythonAnalysisTool.class),
                    mock(PythonSandboxRuntime.class), "分析", mock(AiAgentTraceMapper.class), mapper,
                    null, null, new ToolJsonInterceptor(mapper), null, new AnalysisResultTool(store), datasets, store, null, null, null);

            var result = service.analyzeForMainAgent(new AnalysisCommand("分析库存", null, identity, null));
            assertThat(result.completion()).isEqualTo("FAILED");
            assertThat(result.errorCode()).isEqualTo("INVALID_ANALYSIS_OUTPUT");
            assertThat(result.summary()).contains("格式无效");
            assertThat(result.analysisResults()).extracting(AnalysisResult.File::resultId)
                    .containsExactly("saved-before-error");
            verify(model, times(4)).call(any(Prompt.class));
        }
    }

    /** 完成与缺少信息状态均按模型交接内容返回。 */
    @Test
    void returnsCompletionAndNeedInfo() throws Exception {
        for (String completion : List.of("COMPLETED", "NEED_INFO")) {
            var model = mock(ChatModel.class);
            when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(
                    new AssistantMessage("{\"completion\":\"" + completion + "\",\"summary\":\"任务结论\"}")))));
            var datasets = mock(DatasetManager.class);
            when(datasets.listReady(10, 20)).thenReturn(List.of());
            var store = mock(AnalysisResultStore.class);
            var mapper = new ObjectMapper();
            var service = new AnalysisAgentService(model, mock(PythonAnalysisTool.class),
                    mock(PythonSandboxRuntime.class), "分析", mock(AiAgentTraceMapper.class), mapper,
                    null, null, new ToolJsonInterceptor(mapper), null, new AnalysisResultTool(store), datasets, store, null, null, null);

            var result = service.analyzeForMainAgent(new AnalysisCommand("分析库存", null, new AgentToolContext(10, 20), null));
            assertThat(result.completion()).isEqualTo(completion);
            assertThat(result.summary()).isEqualTo("任务结论");
        }
    }

    /** 入口自动注入预览；仅成功读取的资料和服务端文件进入结果。 */
    @Test
    void assemblesTrustedDatasetsAndSavedResults() throws Exception {
        var model = mock(ChatModel.class);
        var promptText = new AtomicReference<String>();
        var call = new AssistantMessage.ToolCall("read", "function", "readDataset", "{\"datasetId\":\"ds-used\"}");
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            promptText.set(prompt.toString());
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call)).build())));
        }).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"completion\":\"PARTIAL\",\"summary\":\"已分析，范围有限\"}")))));
        var identity = new AgentToolContext(10, 20);
        var datasets = mock(DatasetManager.class);
        var used = new DatasetRecord("ds-used", "run", 10, 20, "库存", List.of(), 3, true, "READY", null, null);
        var unused = new DatasetRecord("ds-only-preview", "run", 10, 20, "安全库存", List.of(), 1, true, "READY", null, null);
        when(datasets.listReady(10, 20)).thenReturn(List.of(used, unused));
        when(datasets.readPage(eq("ds-used"), eq(10L), eq(20L), anyInt(), anyInt()))
                .thenReturn(new DatasetPage(List.of(), List.of(List.of("A", 3)), 3, 1, true));
        when(datasets.readPage(eq("ds-only-preview"), eq(10L), eq(20L), anyInt(), anyInt()))
                .thenReturn(new DatasetPage(List.of(), List.of(List.of("B", 5)), 1, 1, false));
        when(datasets.get("ds-used", 10, 20)).thenReturn(used);
        when(datasets.get("ds-only-preview", 10, 20)).thenReturn(unused);
        var store = mock(AnalysisResultStore.class);
        var file = new AiAnalysisResult();
        file.setResultId("result-1");
        file.setName("分析.csv");
        file.setFileType("csv");
        when(store.list(eq(identity), anyString())).thenReturn(List.of(file));
        var runtime = mock(PythonSandboxRuntime.class);
        var mapper = new ObjectMapper();
        var service = new AnalysisAgentService(model, mock(PythonAnalysisTool.class), runtime, "分析",
                mock(AiAgentTraceMapper.class), mapper, new SqlQueryTool(mock(SqlGraphService.class)),
                new ReadDatasetTool(datasets), new ToolJsonInterceptor(mapper), new ListDatasetsTool(datasets),
                new AnalysisResultTool(store), datasets, store, null, null, null);
        var result = service.analyzeForMainAgent(new AnalysisCommand("分析库存", "仓库一", identity, null));
        assertThat(result.completion()).isEqualTo("PARTIAL");
        assertThat(result.summary()).isEqualTo("已分析，范围有限");
        assertThat(promptText.get()).contains("ds-used", "ds-only-preview", "仓库一");
        assertThat(result.datasets()).extracting(AnalysisResult.Dataset::datasetId).containsExactly("ds-used");
        assertThat(result.providedDatasets()).extracting(AnalysisResult.Dataset::datasetId)
                .containsExactly("ds-used", "ds-only-preview");
        assertThat(result.analysisResults()).extracting(AnalysisResult.File::resultId).containsExactly("result-1");
        verify(datasets).listReady(10, 20);
        verify(datasets, atLeastOnce()).get("ds-used", 10, 20);
        verify(runtime, never()).openSession();
    }

    /** 模型失败返回明确失败状态，不伪造分析成功。 */
    @Test
    void returnsFailedOnModelError() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("模型暂不可用"));
        var datasets = mock(DatasetManager.class);
        when(datasets.listReady(10, 20)).thenReturn(List.of());
        var mapper = new ObjectMapper();
        var store = mock(AnalysisResultStore.class);
        var service = new AnalysisAgentService(model, mock(PythonAnalysisTool.class), mock(PythonSandboxRuntime.class),
                "分析", mock(AiAgentTraceMapper.class), mapper, null, null, new ToolJsonInterceptor(mapper), null,
                new AnalysisResultTool(store), datasets, store, null, null, null);
        var result = service.analyzeForMainAgent(new AnalysisCommand("分析库存", null, new AgentToolContext(10, 20), null));
        assertThat(result.completion()).isEqualTo("FAILED");
        assertThat(result.analysisTaskId()).isNotBlank();
        assertThat(result.summary()).contains("中断");
    }

    /** 模型中断时，仍向主智能体交付已保存的结果文件。 */
    @Test
    void preservesSavedFilesWhenAnalysisFails() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("模型暂不可用"));
        var identity = new AgentToolContext(10, 20);
        var datasets = mock(DatasetManager.class);
        when(datasets.listReady(10, 20)).thenReturn(List.of());
        var saved = new AiAnalysisResult();
        saved.setResultId("saved-before-error");
        saved.setName("分析.csv");
        var store = mock(AnalysisResultStore.class);
        when(store.list(eq(identity), anyString())).thenReturn(List.of(saved));
        var mapper = new ObjectMapper();
        var service = new AnalysisAgentService(model, mock(PythonAnalysisTool.class), mock(PythonSandboxRuntime.class),
                "分析", mock(AiAgentTraceMapper.class), mapper, null, null, new ToolJsonInterceptor(mapper), null,
                new AnalysisResultTool(store), datasets, store, null, null, null);
        var result = service.analyzeForMainAgent(new AnalysisCommand("分析库存", null, identity, null));
        assertThat(result.completion()).isEqualTo("FAILED");
        assertThat(result.analysisResults()).extracting(AnalysisResult.File::resultId).containsExactly("saved-before-error");
    }

    /** 输入组装失败仍记录任务流水，并返回可识别的失败类型。 */
    @Test
    void recordsInputPreparationFailure() {
        var identity = new AgentToolContext(10, 20);
        var datasets = mock(DatasetManager.class);
        when(datasets.listReady(10, 20)).thenThrow(new IllegalStateException("数据服务不可用"));
        var traces = mock(AiAgentTraceMapper.class);
        var mapper = new ObjectMapper();
        var store = mock(AnalysisResultStore.class);
        var service = new AnalysisAgentService(mock(ChatModel.class), mock(PythonAnalysisTool.class),
                mock(PythonSandboxRuntime.class), "分析", traces, mapper, null, null,
                new ToolJsonInterceptor(mapper), null, new AnalysisResultTool(store), datasets, store, null, null, null);
        var result = service.analyzeForMainAgent(new AnalysisCommand("分析库存", null, identity, null));
        assertThat(result.completion()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("ANALYSIS_INPUT_FAILED");
        verify(traces).insert(eq(10L), eq(20L), eq(result.analysisTaskId()), eq("ANALYSIS"), eq("INPUT"), anyString());
        verify(traces).insert(eq(10L), eq(20L), eq(result.analysisTaskId()), eq("ANALYSIS"), eq("ERROR"), anyString());
    }
}
