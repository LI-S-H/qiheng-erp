package com.qiheng.erp.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.sql.graph.model.SqlModelClient;
import com.qiheng.erp.ai.sql.graph.model.output.SqlNeedInfoOutput;
import com.qiheng.erp.ai.sql.graph.model.output.SqlQueryOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** 使用真实模型检查对象名称、编号与零行结果的判断边界。 */
@EnabledIfEnvironmentVariable(named = "ERP_REAL_QWEN_IT", matches = "true")
class RealSqlFilterPromptIT {
    private static final String STOCK_SCHEMA = """
            表 stock：id BIGINT 主键，warehouse_id BIGINT 仓库编号，quantity DECIMAL 库存数量；无需软删除。
            """;

    /** 明确提供编号时，模型应使用该编号查询。 */
    @Test
    void generatesForExplicitId() throws Exception {
        var input = input(STOCK_SCHEMA, "查询仓库编号为 2 的库存明细");
        var result = client().generate(SqlModelClient.loadPrompt("prompts/sql/generate.md"), input);
        System.out.println("FILTER_EXPLICIT_ID=" + result);
        assertThat(result).isInstanceOf(SqlQueryOutput.class);
        assertThat(((SqlQueryOutput) result).sql()).containsIgnoringCase("warehouse_id").contains("2");
    }

    /** 名称可通过已知关系查询，不能直接猜编号。 */
    @Test
    void generatesForNameWithKnownRelation() throws Exception {
        var schema = STOCK_SCHEMA + "表 warehouse：id BIGINT 主键，name VARCHAR 仓库名称；stock.warehouse_id = warehouse.id；无需软删除。";
        var result = client().generate(SqlModelClient.loadPrompt("prompts/sql/generate.md"), input(schema, "查询名称为东仓的库存明细"));
        System.out.println("FILTER_NAME_RELATION=" + result);
        assertThat(result).isInstanceOf(SqlQueryOutput.class);
        assertThat(((SqlQueryOutput) result).sql()).containsIgnoringCase("warehouse").contains("东仓");
    }

    /** 缺少名称与编号的映射时应反馈缺少信息。 */
    @Test
    void requestsMissingNameMapping() throws Exception {
        var result = client().generate(SqlModelClient.loadPrompt("prompts/sql/generate.md"), input(STOCK_SCHEMA, "查询名称为东仓的库存明细"));
        System.out.println("FILTER_MISSING_MAPPING=" + result);
        assertThat(result).isInstanceOf(SqlNeedInfoOutput.class);
    }

    /** 名称与数值编号直接比较时，即使零行也应返回重试。 */
    @Test
    void rejectsNameComparedToNumericId() throws Exception {
        var input = inspection("查询名称为仓库2的库存明细", "SELECT id, warehouse_id, quantity FROM stock WHERE warehouse_id = '仓库2'");
        var result = client().inspect(SqlModelClient.loadPrompt("prompts/sql/inspect.md"), input);
        System.out.println("FILTER_WRONG_COMPARISON=" + result);
        assertThat(result.decision()).isEqualTo("RETRY");
    }

    /** 正确条件查询到零行时不应机械拦截。 */
    @Test
    void acceptsValidEmptyResult() throws Exception {
        var input = inspection("查询仓库编号为 2 的库存明细", "SELECT id, warehouse_id, quantity FROM stock WHERE warehouse_id = 2");
        var result = client().inspect(SqlModelClient.loadPrompt("prompts/sql/inspect.md"), input);
        System.out.println("FILTER_VALID_EMPTY=" + result);
        assertThat(result.decision()).isEqualTo("PASS");
    }

    /** 构建与运行节点相同的模型输入。 */
    private Map<String, Object> input(String schema, String goal) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schema", schema);
        input.put("requirement", Map.of("goal", goal, "dataRequirement", "FULL", "grain", "每条库存记录一行"));
        input.put("knowledge", "warehouse_id 是仓库编号，quantity 是库存数量。");
        input.put("supplements", List.of());
        input.put("currentDate", "2026-11-09");
        input.put("timeZone", "Asia/Shanghai");
        input.put("pageSize", 200);
        input.put("maxTotalRows", 2000);
        input.put("previousSql", "");
        input.put("feedback", "");
        return input;
    }

    /** 构建一个零行 SQL 检查输入。 */
    private Map<String, Object> inspection(String goal, String sql) {
        var input = input(STOCK_SCHEMA, goal);
        input.put("sql", sql);
        input.put("needFullData", true);
        input.put("completed", true);
        input.put("limitations", "");
        input.put("rowCount", 0);
        input.put("columns", List.of(Map.of("name", "id", "label", "id"),
                Map.of("name", "warehouse_id", "label", "warehouse_id"),
                Map.of("name", "quantity", "label", "quantity")));
        input.put("sampleRows", List.of());
        return input;
    }

    /** 按现有集成测试方式加载真实模型配置。 */
    private SqlModelClient client() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        String thinking = settings.getProperty("erp.ai.model.enable-thinking");
        Boolean enabled = thinking == null || thinking.isBlank() ? null : Boolean.valueOf(thinking.trim());
        var model = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.flash"),
                0.2, Duration.ofSeconds(90), enabled);
        return new SqlModelClient(SqlGraphConfiguration.sqlModel(model), new ObjectMapper());
    }
}
