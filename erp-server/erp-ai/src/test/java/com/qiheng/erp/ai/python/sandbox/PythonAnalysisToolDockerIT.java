package com.qiheng.erp.ai.python.sandbox;

import com.qiheng.erp.ai.analysis.AnalysisTaskContext;

import com.qiheng.erp.ai.python.tool.PythonAnalysisTool;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import com.qiheng.erp.ai.python.tool.PythonDatasetPreparer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetInput;
import com.qiheng.erp.ai.dataset.DatasetRecord;
import com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import com.qiheng.erp.ai.domain.entity.AiAnalysisResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Types;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 手动启用：使用本地测试镜像验证真实沙箱工具链路。 */
@EnabledIfEnvironmentVariable(named = "ERP_PYTHON_SANDBOX_IT", matches = "true")
class PythonAnalysisToolDockerIT {
    @TempDir Path resultsDirectory;

    /** 两个任务并发使用共享工具时，容器内同路径的数据互不干扰。 */
    @Test
    void isolatesConcurrentTaskSandboxesAndClosesIndependently() throws Exception {
        var sdkLogger = (Logger) LoggerFactory.getLogger("io.agentscope.runtime.sandbox.manager.SandboxService");
        Level originalLevel = sdkLogger.getLevel();
        sdkLogger.setLevel(Level.WARN);
        var runtime = new PythonSandboxRuntime(new ObjectMapper(), "erp-ai-sandbox-probe:local");
        var first = new AnalysisTaskContext(new AgentToolContext(10, 20), "task-a", runtime);
        var second = new AnalysisTaskContext(new AgentToolContext(11, 21), "task-b", runtime);
        var tool = new PythonAnalysisTool(new PythonDatasetPreparer(mock(DatasetManager.class)), mock(AnalysisResultStore.class));
        var firstContext = new ToolContext(java.util.Map.of(AnalysisTaskContext.CONTEXT_KEY, first));
        var secondContext = new ToolContext(java.util.Map.of(AnalysisTaskContext.CONTEXT_KEY, second));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> tool.executePython("open('/tmp/erp-marker','w').write('FIRST'); print('FIRST')",
                    List.of(), false, null, firstContext));
            var b = pool.submit(() -> tool.executePython("open('/tmp/erp-marker','w').write('SECOND'); print('SECOND')",
                    List.of(), false, null, secondContext));
            assertEquals("SUCCESS", a.get(120, java.util.concurrent.TimeUnit.SECONDS).get("status"));
            assertEquals("SUCCESS", b.get(120, java.util.concurrent.TimeUnit.SECONDS).get("status"));
            var one = tool.executePython("print(open('/tmp/erp-marker').read())", List.of(), false, null, firstContext);
            var two = tool.executePython("print(open('/tmp/erp-marker').read())", List.of(), false, null, secondContext);
            assertEquals("FIRST\n", ((java.util.Map<?, ?>) one.get("textOutput")).get("content"));
            assertEquals("SECOND\n", ((java.util.Map<?, ?>) two.get("textOutput")).get("content"));
            var failed = tool.executePython("raise ValueError('expected')", List.of(), false, null, firstContext);
            assertEquals("FAILED", failed.get("status"));
            first.close();
            var stillRunning = tool.executePython("print(open('/tmp/erp-marker').read())", List.of(), false, null, secondContext);
            assertEquals("SECOND\n", ((java.util.Map<?, ?>) stillRunning.get("textOutput")).get("content"));
        } finally {
            pool.shutdownNow();
            first.close();
            second.close();
            runtime.close();
            sdkLogger.setLevel(originalLevel);
        }
    }

    @Test
    void executesAuthorizedDatasetAndStoresLongOutput() throws Exception {
        // 与应用配置一致：该依赖的 INFO 启动日志包含临时认证令牌。
        var sdkLogger = (Logger) LoggerFactory.getLogger("io.agentscope.runtime.sandbox.manager.SandboxService");
        Level originalLevel = sdkLogger.getLevel();
        sdkLogger.setLevel(Level.WARN);
        var mapper = new ObjectMapper();
        var datasets = mock(DatasetManager.class);
        var record = new DatasetRecord("ds_test", "run", 10, 20, "库存",
                List.of(new SqlQueryColumn("product", "商品", Types.VARCHAR, "VARCHAR", false),
                        new SqlQueryColumn("qty", "数量", Types.INTEGER, "INT", false)),
                2, false, "READY", null, null, "ROW_LIMIT");
        when(datasets.openReady("ds_test", 10, 20)).thenAnswer(call -> new DatasetInput(record,
                new ByteArrayInputStream("[\"PRIVATE_STOCK_7213\",3]\n[\"B\",5]\n".getBytes(StandardCharsets.UTF_8))));
        when(datasets.get("ds_test", 10, 20)).thenReturn(record);
        var store = mock(AnalysisResultStore.class);
        var savedBytes = new java.util.HashMap<String, byte[]>();
        var records = new java.util.HashMap<String, AiAnalysisResult>();
        when(store.save(any(), anyString(), anyString(), anyString(), anyString(), nullable(String.class),
                nullable(String.class), anyList(), any())).thenAnswer(call -> {
            String id = UUID.randomUUID().toString();
            var bytes = new ByteArrayOutputStream();
            call.<AnalysisResultStore.FileWriter>getArgument(8).write(bytes);
            var item = new AiAnalysisResult();
            item.setResultId(id);
            item.setFileType(call.getArgument(6));
            savedBytes.put(id, bytes.toByteArray());
            records.put(id, item);
            return item;
        });
        when(store.open(anyString(), any(), eq(true))).thenAnswer(call ->
                new ByteArrayInputStream(savedBytes.get(call.getArgument(0))));
        when(store.find(anyString(), any())).thenAnswer(call -> records.get(call.getArgument(0)));
        when(store.readText(anyString(), any())).thenAnswer(call -> {
            String id = call.getArgument(0);
            var item = records.get(id);
            return new AnalysisResultStore.TextResult(item.getName(), item.getFileType(),
                    new String(savedBytes.get(id), StandardCharsets.UTF_8));
        });
        var runtime = new PythonSandboxRuntime(mapper, "erp-ai-sandbox-probe:local");
        try (var task = new AnalysisTaskContext(new AgentToolContext(10, 20), "task-1", runtime)) {
            var tool = new PythonAnalysisTool(new PythonDatasetPreparer(datasets), store);
            var taskContext = new ToolContext(java.util.Map.of(AnalysisTaskContext.CONTEXT_KEY, task));
            var noData = tool.executePython("print('第一行'); print('第二行')", List.of(), false, null, taskContext);
            assertEquals("SUCCESS", noData.get("status"));
            assertEquals("第一行\n第二行\n", ((java.util.Map<?, ?>) noData.get("textOutput")).get("content"));
            var invalid = tool.executePython(
                    "import os; os.mkdir('/tmp/erp-analysis/output/invalid')", List.of(), false, null, taskContext);
            assertEquals("PARTIAL", invalid.get("status"));
            var repair = tool.executePython(
                    "import os; os.rmdir('/tmp/erp-analysis/output/invalid')", List.of(), false, null, taskContext);
            assertEquals("PARTIAL", repair.get("status"));
            assertEquals("SUCCESS", tool.executePython("print('已清理')", List.of(), false, null, taskContext).get("status"));
            tool.prepareAnalysisData(List.of("ds_test"), taskContext);
            String code = """
                    import json, pandas as pd
                    marker = 'PRIVATE_CODE_7213'
                    root = '/tmp/erp-analysis/input/'
                    # 列名来自任务上下文，无需沙箱元数据文件。
                    rows = [json.loads(line) for line in open(root + 'ds_test.jsonl', encoding='utf-8')]
                    frame = pd.DataFrame(rows, columns=['product', 'qty'])
                    print('TOTAL', frame['qty'].sum())
                    """;
            var first = tool.executePython(code, List.of("ds_test"), false, null, taskContext);
            assertEquals("SUCCESS", first.get("status"));
            assertTrue(((String) ((java.util.Map<?, ?>) first.get("textOutput")).get("content")).contains("TOTAL 8"));

            var next = tool.prepareAnalysisData(List.of("ds_test"), taskContext);
            String directory = "/tmp/erp-analysis/output";
            String artifactCode = """
                    import os
                    with open(os.path.join('%s', '库存结果.csv'), 'w', encoding='utf-8') as f:
                        f.write('商品,数量\\nA,8\\n')
                    print('分析完成')
                    """.formatted(directory);
            var artifact = tool.executePython(artifactCode, List.of("ds_test"), true, "库存分析", taskContext);
            assertEquals("SUCCESS", artifact.get("status"));
            assertEquals(true, ((java.util.Map<?, ?>) artifact.get("textOutput")).get("saved"));
            var files = (List<?>) artifact.get("files");
            assertEquals(1, files.size());
            var file = (java.util.Map<?, ?>) files.getFirst();
            assertEquals("库存结果.csv", file.get("fileName"));
            assertTrue(new String(savedBytes.get(file.get("resultId")), StandardCharsets.UTF_8).contains("A,8"));

            var identical = tool.executePython(artifactCode, List.of("ds_test"), false, null, taskContext);
            assertEquals("SUCCESS", identical.get("status"));
            assertEquals(List.of(), identical.get("files"));
            var unchanged = tool.executePython("print('不修改文件')", List.of("ds_test"), false, null, taskContext);
            assertEquals("SUCCESS", unchanged.get("status"));
            assertEquals(List.of(), unchanged.get("files"));
            var overwritten = tool.executePython(artifactCode.replace("A,8", "A,9"),
                    List.of("ds_test"), false, null, taskContext);
            assertEquals("SUCCESS", overwritten.get("status"));
            var changedFile = (java.util.Map<?, ?>) ((List<?>) overwritten.get("files")).getFirst();
            assertNotEquals(file.get("resultId"), changedFile.get("resultId"));
            assertTrue(new String(savedBytes.get(changedFile.get("resultId")), StandardCharsets.UTF_8).contains("A,9"));

            tool.prepareAnalysisData(List.of(), taskContext);
            var longOutput = tool.executePython("print('x' * 9000)", List.of(), false, null, taskContext);
            assertEquals("SUCCESS", longOutput.get("status"));
            assertEquals(true, ((java.util.Map<?, ?>) longOutput.get("textOutput")).get("truncated"));
            var resultTool = new AnalysisResultTool(store);
            var context = new ToolContext(java.util.Map.of(AgentToolContext.CONTEXT_KEY, new AgentToolContext(10, 20)));
            var full = resultTool.readAnalysisResult((String) ((java.util.Map<?, ?>) longOutput.get("textOutput")).get("resultId"), context);
            assertEquals(9001, ((String) full.get("content")).length());

            tool.prepareAnalysisData(List.of(), taskContext);
            var failure = tool.executePython("raise ValueError('expected failure')", List.of(), false, null, taskContext);
            assertEquals("FAILED", failure.get("status"));
            assertTrue(((String) failure.get("stderr")).contains("expected failure"));
        } finally {
            runtime.close();
            sdkLogger.setLevel(originalLevel);
        }
    }

    /**
     * 沙箱容器被回收重建后，已准备的数据集文件随之消失；重建后的会话必须重新写入。
     */
    @Test
    void losesPreparedFilesWhenSandboxIsRecreated() throws Exception {
        var sdkLogger = (Logger) LoggerFactory.getLogger("io.agentscope.runtime.sandbox.manager.SandboxService");
        Level originalLevel = sdkLogger.getLevel();
        sdkLogger.setLevel(Level.WARN);
        var mapper = new ObjectMapper();
        var datasets = mock(DatasetManager.class);
        var record = new DatasetRecord("ds_test", "run", 10, 20, "库存",
                List.of(new SqlQueryColumn("product", "商品", Types.VARCHAR, "VARCHAR", false)),
                2, false, "READY", null, null, "ROW_LIMIT");
        when(datasets.openReady("ds_test", 10, 20)).thenAnswer(call -> new DatasetInput(record,
                new ByteArrayInputStream("[\"A\",3]\n".getBytes(StandardCharsets.UTF_8))));
        when(datasets.get("ds_test", 10, 20)).thenReturn(record);
        var runtime = new PythonSandboxRuntime(mapper, "erp-ai-sandbox-probe:local");
        try (var task = new AnalysisTaskContext(new AgentToolContext(10, 20), "task-rebuild", runtime)) {
            var tool = new PythonAnalysisTool(new PythonDatasetPreparer(datasets), mock(AnalysisResultStore.class));
            var taskContext = new ToolContext(java.util.Map.of(AnalysisTaskContext.CONTEXT_KEY, task));

            var prepared = tool.prepareAnalysisData(List.of("ds_test"), taskContext);
            var file = (java.util.Map<?, ?>) ((List<?>) prepared.get("files")).getFirst();
            assertEquals(Boolean.FALSE, file.get("reprepared"));
            // 文件确实写进了沙箱，重复准备时不需要重写。
            assertTrue(task.sandbox().hasInput("ds_test.jsonl"));
            var again = tool.prepareAnalysisData(List.of("ds_test"), taskContext);
            assertEquals(Boolean.FALSE,
                    ((java.util.Map<?, ?>) ((List<?>) again.get("files")).getFirst()).get("reprepared"));

            // 执行失败时把沙箱现状一并交给模型。
            var failure = tool.executePython("raise ValueError('expected')", List.of("ds_test"), false, null, taskContext);
            assertEquals("FAILED", failure.get("status"));
            var files = (java.util.Map<?, ?>) failure.get("sandboxFiles");
            var inputs = (List<?>) files.get("inputs");
            assertEquals(1, inputs.size());
            var input = (java.util.Map<?, ?>) inputs.getFirst();
            assertEquals("ds_test", input.get("datasetId"));
            assertEquals(Boolean.TRUE, input.get("present"));
            assertTrue(files.containsKey("outputs"));
            assertTrue(files.containsKey("registeredResults"));
        } finally {
            runtime.close();
            sdkLogger.setLevel(originalLevel);
        }
        // 重建后的沙箱是空的：同样的文件名查不到，这正是需要重新准备的原因。
        try (var runtime2 = new PythonSandboxRuntime(mapper, "erp-ai-sandbox-probe:local")) {
            try (var fresh = runtime2.openSession()) {
                assertFalse(fresh.hasInput("ds_test.jsonl"));
            } finally {
                runtime2.close();
            }
        }
    }

    @Test
    void limitsOutputAndStopsChildProcessesOnTimeout() throws Exception {
        var sdkLogger = (Logger) LoggerFactory.getLogger("io.agentscope.runtime.sandbox.manager.SandboxService");
        Level originalLevel = sdkLogger.getLevel();
        sdkLogger.setLevel(Level.WARN);
        var mapper = new ObjectMapper();
        var runtime = new PythonSandboxRuntime(mapper, "erp-ai-sandbox-probe:local");
        try (var sandbox = runtime.openSession()) {
            var protocol = new PythonExecutionProtocol(mapper);
            var limited = protocol.parse(sandbox.runBootstrap(protocol.wrap(
                    "print('x' * 50000)", UUID.randomUUID().toString(), 10, 100, 1024)));
            assertFalse(limited.success());
            assertEquals("OUTPUT_LIMIT", limited.errorType());
            assertTrue(limited.stdoutBytes() <= 1024);

            String code = """
                    import subprocess, sys, time
                    child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'])
                    open('/tmp/erp-analysis/child.pid', 'w').write(str(child.pid))
                    time.sleep(30)
                    """;
            var timeout = protocol.parse(sandbox.runBootstrap(protocol.wrap(
                    code, UUID.randomUUID().toString(), 1, 100, 1024)));
            assertFalse(timeout.success());
            assertEquals("TIMEOUT", timeout.errorType());
            String check = sandbox.runBootstrap("""
                    import os
                    pid = open('/tmp/erp-analysis/child.pid').read()
                    try:
                        state = open('/proc/' + pid + '/stat').read().split()[2]
                        print('CHILD_STOPPED', state == 'Z')
                    except FileNotFoundError:
                        print('CHILD_STOPPED', True)
                    """);
            assertTrue(check.contains("CHILD_STOPPED True"));
        } finally {
            runtime.close();
            sdkLogger.setLevel(originalLevel);
        }
    }
}
