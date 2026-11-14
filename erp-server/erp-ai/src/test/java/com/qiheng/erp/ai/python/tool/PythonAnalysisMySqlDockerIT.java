package com.qiheng.erp.ai.python.tool;

import com.qiheng.erp.ai.analysis.AnalysisTaskContext;

import com.qiheng.erp.ai.python.sandbox.PythonSandboxRuntime;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.dataset.DatasetInput;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetRecord;
import com.qiheng.erp.ai.mapper.AiAnalysisResultMapper;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn;
import com.qiheng.erp.ai.context.AgentToolContext;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.ai.chat.model.ToolContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 显式启用时验证真实 Docker、MySQL 和文件存储的联动。 */
@EnabledIfEnvironmentVariable(named = "ERP_PYTHON_SANDBOX_IT", matches = "true")
class PythonAnalysisMySqlDockerIT {
    @TempDir Path directory;

    @Test
    void persistsGeneratedChineseCsvAfterSandboxCloses() throws Exception {
        String url = System.getenv("DATASET_IT_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "需要隔离的 DATASET_IT_URL");
        var source = new DriverManagerDataSource(url, System.getenv("DATASET_IT_USER"), System.getenv("DATASET_IT_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        jdbc.execute(Files.readString(Path.of("..", "..", "docs", "database", "sql", "034_ai_analysis_result.sql")));
        var configuration = new MybatisConfiguration();
        configuration.addMapper(AiAnalysisResultMapper.class);
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        var repository = new SqlSessionTemplate(bean.getObject()).getMapper(AiAnalysisResultMapper.class);
        var mapper = new ObjectMapper();
        var datasets = mock(DatasetManager.class);
        long userId = 900000000000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100000000L);
        var identity = new AgentToolContext(userId, 987652);
        var dataset = new DatasetRecord("ds_test", "run", userId, 987652, "库存",
                List.of(new SqlQueryColumn("name", "商品", Types.VARCHAR, "VARCHAR", false)),
                1, true, "READY", null, null);
        when(datasets.get("ds_test", userId, 987652)).thenReturn(dataset);
        when(datasets.openReady("ds_test", userId, 987652)).thenAnswer(call -> new DatasetInput(dataset,
                new ByteArrayInputStream("[\"商品甲\"]\n".getBytes(StandardCharsets.UTF_8))));
        var store = new AnalysisResultStore(repository, datasets, mapper, directory.toString(), 0);
        var sdkLogger = (Logger) LoggerFactory.getLogger("io.agentscope.runtime.sandbox.manager.SandboxService");
        Level previousLevel = sdkLogger.getLevel();
        sdkLogger.setLevel(Level.WARN);
        var runtime = new PythonSandboxRuntime(mapper, "erp-ai-sandbox-probe:local");
        String resultId;
        try {
            try (var task = new AnalysisTaskContext(identity, "task-1", runtime)) {
                var tool = new PythonAnalysisTool(new PythonDatasetPreparer(datasets), store);
                var taskContext = new ToolContext(Map.of(AnalysisTaskContext.CONTEXT_KEY, task));
                var prepared = tool.prepareAnalysisData(List.of("ds_test"), taskContext);
                var files = (List<?>) prepared.get("files");
                var file = (Map<?, ?>) files.getFirst();
                String inputPath = (String) file.get("filePath");
                String outputPath = "/tmp/erp-analysis/output";
                String code = """
                        import json, os
                        with open('%s', encoding='utf-8') as source:
                            row = json.loads(source.readline())
                        with open(os.path.join('%s', '库存结果.csv'), 'w', encoding='utf-8') as result:
                            result.write('商品\\n' + row[0] + '\\n')
                        print('分析完成')
                        """.formatted(inputPath, outputPath);
                var response = tool.executePython(code, List.of("ds_test"), false, null, taskContext);
                assertEquals("SUCCESS", response.get("status"), response.toString());
                var artifacts = (List<?>) response.get("files");
                assertEquals(1, artifacts.size());
                var artifact = (Map<?, ?>) artifacts.getFirst();
                assertEquals("库存结果.csv", artifact.get("fileName"));
                resultId = (String) artifact.get("resultId");
            }
            var stored = store.find(resultId, identity);
            assertEquals("库存结果.csv", stored.getName());
            assertEquals(List.of("ds_test"), mapper.readValue(stored.getSourceDatasetIds(), List.class));
            try (var input = store.open(resultId, identity)) {
                assertEquals("商品\n商品甲\n", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            // 沙箱关闭后仍能通过独立工具查询服务端结果。
            var resultTool = new AnalysisResultTool(store);
            var context = new ToolContext(Map.of(AgentToolContext.CONTEXT_KEY, identity));
            assertEquals("商品\n商品甲\n", resultTool.readAnalysisResult(resultId, context).get("content"));
            assertEquals(1, ((List<?>) resultTool.listAnalysisResults("task-1", context).get("results")).size());
        } finally {
            runtime.close();
            sdkLogger.setLevel(previousLevel);
            jdbc.update("DELETE FROM ai_analysis_result WHERE user_id = ? AND conversation_id = ?", userId, 987652);
        }
    }
}

