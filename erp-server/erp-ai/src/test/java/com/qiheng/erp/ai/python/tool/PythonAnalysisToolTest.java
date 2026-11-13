package com.qiheng.erp.ai.python.tool;

import com.qiheng.erp.ai.python.sandbox.PythonSandboxSession;
import com.qiheng.erp.ai.python.sandbox.PythonSandboxRuntime;
import com.qiheng.erp.ai.analysis.AnalysisTaskContext;
import org.springframework.ai.chat.model.ToolContext;
import com.qiheng.erp.ai.python.sandbox.PythonExecutionResult;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import com.qiheng.erp.ai.domain.entity.AiAnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PythonAnalysisToolTest {
    /** 多个任务共享工具时各自懒创建并关闭沙箱，同一任务重复使用会话。 */
    @Test
    void isolatesAndClosesTaskSandboxes() throws Exception {
        var runtime = mock(PythonSandboxRuntime.class);
        var firstSession = mock(PythonSandboxSession.class);
        var secondSession = mock(PythonSandboxSession.class);
        when(runtime.openSession()).thenReturn(firstSession, secondSession);
        var first = new AnalysisTaskContext(new AgentToolContext(10, 20), "task-1", runtime);
        var second = new AnalysisTaskContext(new AgentToolContext(11, 21), "task-2", runtime);
        var tool = new PythonAnalysisTool(mock(PythonDatasetPreparer.class), mock(AnalysisResultStore.class));
        var firstContext = new ToolContext(Map.of(AnalysisTaskContext.CONTEXT_KEY, first));
        var secondContext = new ToolContext(Map.of(AnalysisTaskContext.CONTEXT_KEY, second));
        tool.prepareAnalysisData(List.of(), firstContext);
        verify(runtime, never()).openSession();
        when(firstSession.execute(anyString(), anyInt(), anyInt())).thenReturn(
                new PythonExecutionResult(true, "first", "", 5, false, "output", null));
        when(secondSession.execute(anyString(), anyInt(), anyInt())).thenReturn(
                new PythonExecutionResult(true, "second", "", 6, false, "output", null));
        tool.executePython("first", List.of(), false, null, firstContext);
        tool.executePython("first again", List.of(), false, null, firstContext);
        tool.executePython("second", List.of(), false, null, secondContext);
        verify(runtime, times(2)).openSession();
        verify(firstSession, times(2)).execute(anyString(), anyInt(), anyInt());
        verify(secondSession).execute(eq("second"), anyInt(), anyInt());
        first.close();
        verify(firstSession).close();
        verify(secondSession, never()).close();
        second.close();
        verify(secondSession).close();
    }

    @Test
    void keepsSavedTextWhenOutputScanFailsAfterExecution() throws Exception {
        var session = mock(PythonSandboxSession.class);
        var results = mock(AnalysisResultStore.class);
        var identity = new AgentToolContext(10, 20);
        var tool = new PythonAnalysisTool(mock(PythonDatasetPreparer.class), results);
        var context = context(identity, session);
        when(session.listArtifacts("/tmp/erp-analysis/output"))
                .thenReturn(List.of())
                .thenThrow(new java.io.IOException("扫描失败"));
        when(session.execute(anyString(), anyInt(), anyInt())).thenReturn(
                new PythonExecutionResult(true, "计算完成", "", 12, false, "output", null));
        var saved = new AiAnalysisResult();
        saved.setResultId("text-result-1");
        when(results.save(eq(identity), anyString(), anyString(), eq("TEXT"), eq("摘要"), isNull(),
                eq("txt"), eq(List.of()), any())).thenReturn(saved);

        var response = tool.executePython("print('计算完成')", List.of(), true, "摘要", context);

        assertEquals("PARTIAL", response.get("status"));
        var text = (Map<?, ?>) response.get("textOutput");
        assertEquals("计算完成", text.get("content"));
        assertEquals(true, text.get("saved"));
        assertEquals("text-result-1", text.get("resultId"));
        assertEquals(false, response.get("pendingFilesKnown"));
        assertEquals(List.of(), response.get("files"));
        verify(results).save(eq(identity), anyString(), anyString(), eq("TEXT"), eq("摘要"), isNull(),
                eq("txt"), eq(List.of()), any());
    }

    @Test
    void damagedOutputDirectoryDoesNotBlockRepairCode() throws Exception {
        var session = mock(PythonSandboxSession.class);
        var tool = new PythonAnalysisTool(mock(PythonDatasetPreparer.class), mock(AnalysisResultStore.class));
        var context = context(new AgentToolContext(10, 20), session);
        when(session.listArtifacts("/tmp/erp-analysis/output"))
                .thenThrow(new java.io.IOException("目录中有子目录"))
                .thenReturn(List.of());
        when(session.execute(anyString(), anyInt(), anyInt())).thenReturn(
                new PythonExecutionResult(true, "已清理", "", 9, false, "output", null));

        var response = tool.executePython("清理输出目录", List.of(), false, null, context);

        assertEquals("PARTIAL", response.get("status"));
        assertTrue(((String) response.get("message")).contains("无法确认"));
        verify(session).execute("清理输出目录", 60, 8000);
    }

    @Test
    void exposesSeparateToolSchemasWithoutIdentityArguments() {
        var tool = new PythonAnalysisTool(mock(PythonDatasetPreparer.class), mock(AnalysisResultStore.class));
        var callbacks = org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                .toolObjects(tool).build().getToolCallbacks();
        var definitions = java.util.Arrays.stream(callbacks).collect(java.util.stream.Collectors.toMap(
                callback -> callback.getToolDefinition().name(), callback -> callback.getToolDefinition().inputSchema()));
        assertTrue(definitions.containsKey("prepareAnalysisData"));
        assertTrue(definitions.containsKey("executePython"));
        assertFalse(definitions.containsKey("readAnalysisResult"));
        assertTrue(definitions.get("executePython").contains("saveResult"));
        assertFalse(definitions.get("executePython").contains("userId"));
    }

    @Test
    void preparesStableFilesAndReturnsAlreadySavedIdsWhenFileSaveFails() throws Exception {
        var session = mock(PythonSandboxSession.class);
        var preparer = mock(PythonDatasetPreparer.class);
        var results = mock(AnalysisResultStore.class);
        var identity = new AgentToolContext(10, 20);
        String directory = "/tmp/erp-analysis/output";
        when(preparer.prepare(session, identity, "ds_1")).thenReturn(Map.of("datasetId", "ds_1"));
        when(preparer.prepare(session, identity, "ds_2")).thenReturn(Map.of("datasetId", "ds_2"));
        when(session.execute(anyString(), anyInt(), anyInt())).thenReturn(
                new PythonExecutionResult(true, "ok", "", 2, false, "output", null));
        when(session.listArtifacts(directory)).thenReturn(List.of(), List.of(new PythonSandboxSession.SandboxArtifact("结果.csv", 2, "changed")));
        var saved = new AiAnalysisResult();
        saved.setResultId("result_1");
        when(results.save(any(), anyString(), anyString(), anyString(), anyString(), nullable(String.class),
                nullable(String.class), anyList(), any())).thenReturn(saved)
                .thenThrow(new java.io.IOException("保存失败"));
        var tool = new PythonAnalysisTool(preparer, results);
        var context = context(identity, session);
        tool.prepareAnalysisData(List.of("ds_1"), context);
        tool.prepareAnalysisData(List.of("ds_2", "ds_1"), context);
        var response = tool.executePython("print('ok')", List.of("ds_2"), true, "测试", context);
        assertEquals("PARTIAL", response.get("status"));
        assertEquals("result_1", ((Map<?, ?>) response.get("textOutput")).get("resultId"));
        assertEquals(List.of(), response.get("files"));
        assertEquals(List.of("结果.csv"), response.get("pendingFiles"));
        verify(preparer, times(1)).prepare(session, identity, "ds_1");
        verify(preparer, times(1)).prepare(session, identity, "ds_2");
        verify(results, times(2)).save(eq(identity), anyString(), anyString(), anyString(), anyString(),
                nullable(String.class), nullable(String.class), eq(List.of("ds_1", "ds_2")), any());
    }

    @Test
    void rechecksPreviouslyLoadedDataBeforeReusingTheSandbox() throws Exception {
        var session = mock(PythonSandboxSession.class);
        var preparer = mock(PythonDatasetPreparer.class);
        var results = mock(AnalysisResultStore.class);
        var identity = new AgentToolContext(10, 20);
        when(preparer.prepare(session, identity, "ds_1")).thenReturn(Map.of("datasetId", "ds_1"));
        when(session.execute(anyString(), anyInt(), anyInt())).thenReturn(
                new PythonExecutionResult(true, "ok", "", 2, false, "output", null));
        var tool = new PythonAnalysisTool(preparer, results);
        var context = context(identity, session);

        tool.prepareAnalysisData(List.of("ds_1"), context);
        assertEquals("SUCCESS", tool.executePython("print('ok')", List.of("ds_1"), false, null, context).get("status"));
        tool.prepareAnalysisData(List.of(), context);
        doThrow(new IllegalArgumentException("权限已撤销")).when(preparer).verify("ds_1", identity);
        assertThrows(IllegalArgumentException.class, () -> tool.executePython("print('again')", List.of(), false, null, context));
        verify(session, times(1)).execute(anyString(), anyInt(), anyInt());
    }
    private ToolContext context(AgentToolContext identity, PythonSandboxSession session) {
        var runtime = mock(PythonSandboxRuntime.class);
        when(runtime.openSession()).thenReturn(session);
        var task = new AnalysisTaskContext(identity, "task-1", runtime);
        return new ToolContext(Map.of(AnalysisTaskContext.CONTEXT_KEY, task));
    }
}

