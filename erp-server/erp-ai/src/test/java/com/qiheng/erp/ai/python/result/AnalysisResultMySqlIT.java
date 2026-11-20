package com.qiheng.erp.ai.python.result;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetRecord;
import com.qiheng.erp.ai.mapper.AiAnalysisResultMapper;
import com.qiheng.erp.ai.context.AgentToolContext;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 使用独立测试数据库验证真实 MySQL 与 MyBatis 映射。 */
class AnalysisResultMySqlIT {
    @TempDir Path directory;

    @Test
    void storesChineseFileAndJsonAndRejectsOtherOwners() throws Exception {
        String url = System.getenv("DATASET_IT_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "需要隔离的 DATASET_IT_URL");
        var source = new DriverManagerDataSource(url, System.getenv("DATASET_IT_USER"), System.getenv("DATASET_IT_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        jdbc.execute(Files.readString(Path.of("..", "..", "docs", "database", "sql", "034_ai_analysis_result.sql")));
        // 过期时间由后续迁移补列，建表后必须执行，否则实体查询缺 expires_at。
        jdbc.execute(Files.readString(Path.of("..", "..", "docs", "database", "sql", "041_ai_data_retention.sql")));
        var configuration = new MybatisConfiguration();
        configuration.addMapper(AiAnalysisResultMapper.class);
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        var repository = new SqlSessionTemplate(bean.getObject()).getMapper(AiAnalysisResultMapper.class);
        var datasets = mock(DatasetManager.class);
        var identity = new AgentToolContext(987651, 987652);
        var dataset = new DatasetRecord("ds_test", "run", 987651, 987652, "测试", List.of(), 1,
                true, "READY", null, null);
        when(datasets.get("ds_test", 987651, 987652)).thenReturn(dataset);
        var store = new AnalysisResultStore(repository, datasets, new ObjectMapper(), directory.toString(), 0);
        String task = "it_" + UUID.randomUUID();
        try {
            var saved = store.save(identity, task, "execution", "FILE", "低库存商品.csv", "结果", "csv",
                    List.of("ds_test"), out -> out.write("商品,数量\n库存,1".getBytes(StandardCharsets.UTF_8)));
            var loaded = store.find(saved.getResultId(), identity);
            assertEquals("低库存商品.csv", loaded.getName());
            assertEquals(List.of("ds_test"), new ObjectMapper().readValue(loaded.getSourceDatasetIds(), List.class));
            try (var input = store.open(saved.getResultId(), identity)) {
                assertEquals("商品,数量\n库存,1", new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertTrue(store.list(identity, task).stream().anyMatch(item -> item.getResultId().equals(saved.getResultId())));
            assertThrows(IllegalArgumentException.class, () -> store.find(saved.getResultId(), new AgentToolContext(987653, 987652)));
            assertThrows(IllegalArgumentException.class, () -> store.find(saved.getResultId(), new AgentToolContext(987651, 987654)));
            when(datasets.get("ds_test", 987651, 987652)).thenThrow(new IllegalArgumentException("权限已撤销"));
            assertThrows(IllegalArgumentException.class, () -> store.open(saved.getResultId(), identity));
        } finally {
            jdbc.update("DELETE FROM ai_analysis_result WHERE analysis_task_id = ?", task);
        }
    }
}
