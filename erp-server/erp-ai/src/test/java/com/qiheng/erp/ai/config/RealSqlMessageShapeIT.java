package com.qiheng.erp.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.sql.graph.model.SqlModelClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 使用真实模型观察 SQL 生成和检查节点的请求与响应消息形态。 */
@EnabledIfEnvironmentVariable(named = "ERP_REAL_SQL_SHAPE_IT", matches = "true")
class RealSqlMessageShapeIT {
    @Test
    void observesActualSqlModelCalls() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        String thinking = settings.getProperty("erp.ai.model.enable-thinking");
        Boolean enabled = thinking == null || thinking.isBlank() ? null : Boolean.valueOf(thinking.trim());
        var model = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.flash"),
                0.2, Duration.ofSeconds(90), enabled);
        var delegate = SqlGraphConfiguration.sqlModel(model);
        var observed = mock(ChatModel.class);
        var count = new AtomicInteger();
        when(observed.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            var response = delegate.call(prompt);
            var output = response.getResult().getOutput();
            System.out.println("SQL_SHAPE index=" + count.incrementAndGet()
                    + " inputTypes=" + prompt.getInstructions().stream().map(m -> m.getClass().getSimpleName()).toList()
                    + " outputType=" + output.getClass().getSimpleName()
                    + " outputLength=" + (output.getText() == null ? 0 : output.getText().length())
                    + " toolCalls=" + output.getToolCalls().size());
            assertThat(output.getToolCalls()).isEmpty();
            return response;
        });
        var client = new SqlModelClient(observed, new ObjectMapper());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schema", "表 stock：id 商品编号，warehouse_id 仓库编号，quantity 库存数量，unit_snapshot 单位；无需软删除。");
        input.put("knowledge", "quantity 为库存数量；不同单位不可合计。");
        input.put("supplements", List.of());
        input.put("pageSize", 200);
        input.put("maxTotalRows", 2000);
        input.put("currentDate", "2026-11-08");
        input.put("timeZone", "Asia/Shanghai");
        input.put("previousSql", "");
        input.put("feedback", "");
        String generate = SqlModelClient.loadPrompt("prompts/sql/generate.md");
        input.put("requirement", Map.of("goal", "查询全部仓库每条库存数量及单位", "dataRequirement", "FULL"));
        System.out.println("SQL_CASE all=" + client.generate(generate, input).getClass().getSimpleName());
        input.put("requirement", Map.of("goal", "查询指定仓库的库存", "dataRequirement", "FULL"));
        System.out.println("SQL_CASE missing=" + client.generate(generate, input).getClass().getSimpleName());
        input.put("requirement", Map.of("goal", "查询仓库1库存数量及单位", "dataRequirement", "FULL"));
        input.put("previousSql", "SELECT missing FROM stock");
        input.put("feedback", "字段 missing 不存在，请根据可用字段修正");
        System.out.println("SQL_CASE retry=" + client.generate(generate, input).getClass().getSimpleName());
        input.put("sql", "SELECT warehouse_id, quantity, unit_snapshot FROM stock WHERE warehouse_id=1");
        input.put("needFullData", true);
        input.put("completed", true);
        input.put("limitations", "");
        input.put("rowCount", 1);
        input.put("columns", List.of(Map.of("name", "warehouse_id", "label", "warehouse_id"),
                Map.of("name", "quantity", "label", "quantity"), Map.of("name", "unit_snapshot", "label", "unit_snapshot")));
        input.put("sampleRows", List.of(List.of(1, 10, "件")));
        System.out.println("SQL_CASE inspect=" + client.inspect(SqlModelClient.loadPrompt("prompts/sql/inspect.md"), input).decision());
        assertThat(count.get()).isEqualTo(4);
    }
}
