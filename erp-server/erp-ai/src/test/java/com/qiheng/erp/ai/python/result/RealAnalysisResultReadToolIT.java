package com.qiheng.erp.ai.python.result;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.mapper.AiAnalysisResultMapper;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 使用隔离 MySQL 和真实文件测试分析结果读取工具。 */
class RealAnalysisResultReadToolIT {
    @TempDir Path directory;

    @Test
    void readsSavedCsvAndRejectsOtherConversation() throws Exception {
        String url = System.getenv("DATASET_IT_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank());
        var source = new DriverManagerDataSource(url, System.getenv("DATASET_IT_USER"), System.getenv("DATASET_IT_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        jdbc.execute(Files.readString(Path.of("..", "..", "docs", "database", "sql", "034_ai_analysis_result.sql")));
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE() AND table_name = 'ai_analysis_result' AND column_name = 'expires_at'
                """, Integer.class);
        if (count != null && count == 0) {
            // 仅升级隔离测试库；保留已有记录。
            jdbc.execute("ALTER TABLE ai_analysis_result ADD COLUMN expires_at DATETIME NULL");
        }
        var config = new MybatisConfiguration();
        config.addMapper(AiAnalysisResultMapper.class);
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(config);
        var mapper = new SqlSessionTemplate(bean.getObject()).getMapper(AiAnalysisResultMapper.class);
        var store = new AnalysisResultStore(mapper, mock(DatasetManager.class), new ObjectMapper(), directory.toString(), 0);
        var tool = new AnalysisResultTool(store);
        AgentToolContext identity = new AgentToolContext(987651, 987652);
        String task = "real_tool_" + UUID.randomUUID();
        try {
            var saved = store.save(identity, task, "execution", "FILE", "分析结果.csv", "测试", "csv",
                    List.of(), out -> out.write("商品,数量\nA,7\nB,3".getBytes(StandardCharsets.UTF_8)));
            Map<String, Object> result = tool.readAnalysisResult(saved.getResultId(),
                    new ToolContext(Map.of(AgentToolContext.CONTEXT_KEY, identity)));
            assertThat(result).containsEntry("name", "分析结果.csv")
                    .containsEntry("content", "商品,数量\nA,7\nB,3");
            Map<String, Object> denied = tool.readAnalysisResult(saved.getResultId(),
                    new ToolContext(Map.of(AgentToolContext.CONTEXT_KEY, new AgentToolContext(987651, 987653))));
            assertThat(denied).containsEntry("status", "UNAVAILABLE");
        } finally {
            jdbc.update("DELETE FROM ai_analysis_result WHERE analysis_task_id = ?", task);
        }
    }
}
