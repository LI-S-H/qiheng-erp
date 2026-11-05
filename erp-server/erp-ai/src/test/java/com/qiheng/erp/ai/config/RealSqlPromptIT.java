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

/** 用真实 SQL 模型检验仓库范围缺失和结果复核。 */
@EnabledIfEnvironmentVariable(named = "ERP_REAL_QWEN_IT", matches = "true")
class RealSqlPromptIT {
    /** 用真实模型比较跨单位汇总、按单位汇总和商品计数。 */
    @Test
    void checksQuantityAggregationByBusinessUnit() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        String thinking = settings.getProperty("erp.ai.model.enable-thinking");
        Boolean enabled = thinking == null || thinking.isBlank() ? null : Boolean.valueOf(thinking.trim());
        var model = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.flash"),
                0.2, Duration.ofSeconds(90), enabled);
        var client = new SqlModelClient(SqlGraphConfiguration.sqlModel(model), new ObjectMapper());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schema", "表 stock：warehouse_id 仓库ID，product_id 商品ID，quantity 库存数量，unit_snapshot 数量单位；无需软删除过滤。");
        input.put("knowledge", "quantity 是业务数量，unit_snapshot 是对应单位，存在箱、卷等不同单位，不存在换算关系；相同单位的库存数量可合计。");
        input.put("requirement", Map.of("goal", "查看各仓库库存数量，按适用单位汇总", "dataRequirement", "FULL", "grain", "每个仓库每种单位一行"));
        input.put("supplements", List.of()); input.put("currentDate", "2026-11-04"); input.put("timeZone", "Asia/Shanghai");
        input.put("completed", true); input.put("needFullData", true); input.put("limitations", ""); input.put("rowCount", 1);
        input.put("columns", List.of(Map.of("name", "warehouse_id", "label", "warehouse_id"), Map.of("name", "total_qty", "label", "total_qty")));
        input.put("sampleRows", List.of(List.of(1, 30)));
        String prompt = SqlModelClient.loadPrompt("prompts/sql/inspect.md");
        input.put("sql", "SELECT warehouse_id, SUM(quantity) AS total_qty FROM stock GROUP BY warehouse_id");
        var incorrect = client.inspect(prompt, input);
        System.out.println("跨单位数量汇总检查=" + incorrect);
        assertThat(incorrect.decision()).isEqualTo("RETRY");

        input.put("sql", "SELECT warehouse_id, unit_snapshot, SUM(quantity) AS total_qty FROM stock GROUP BY warehouse_id, unit_snapshot");
        input.put("columns", List.of(Map.of("name", "warehouse_id", "label", "warehouse_id"), Map.of("name", "unit_snapshot", "label", "unit_snapshot"), Map.of("name", "total_qty", "label", "total_qty")));
        input.put("sampleRows", List.of(List.of(1, "箱", 10), List.of(1, "卷", 20))); input.put("rowCount", 2);
        var grouped = client.inspect(prompt, input);
        System.out.println("按单位汇总检查=" + grouped);
        assertThat(grouped.decision()).isEqualTo("PASS");

        input.put("requirement", Map.of("goal", "统计各仓库商品种类数", "dataRequirement", "FULL", "grain", "每个仓库一行"));
        input.put("sql", "SELECT warehouse_id, COUNT(DISTINCT product_id) AS product_count FROM stock GROUP BY warehouse_id");
        input.put("columns", List.of(Map.of("name", "warehouse_id", "label", "warehouse_id"), Map.of("name", "product_count", "label", "product_count")));
        input.put("sampleRows", List.of(List.of(1, 2))); input.put("rowCount", 1);
        var count = client.inspect(prompt, input);
        System.out.println("商品种类计数检查=" + count);
        assertThat(count.decision()).isEqualTo("PASS");

        input.put("requirement", Map.of("goal", "按仓库汇总库存数量", "dataRequirement", "FULL", "grain", "每个仓库一行"));
        input.put("knowledge", "quantity 是库存数量，但无法取得单位及换算关系。");
        input.put("sql", "SELECT warehouse_id, SUM(quantity) AS total_qty FROM stock GROUP BY warehouse_id");
        input.put("columns", List.of(Map.of("name", "warehouse_id", "label", "warehouse_id"), Map.of("name", "total_qty", "label", "total_qty")));
        input.put("sampleRows", List.of(List.of(1, 30))); input.put("rowCount", 1);
        var unknownUnit = client.inspect(prompt, input);
        System.out.println("单位未知汇总检查=" + unknownUnit);
        assertThat(unknownUnit.decision()).isIn("RETRY", "FAILED");

        input.put("knowledge", "quantity 为库存数量；unit_snapshot 取值为箱或件；同类商品一箱明确等于10件，可以统一换算为件后汇总。");
        input.put("sql", "SELECT warehouse_id, SUM(CASE WHEN unit_snapshot = '箱' THEN quantity * 10 ELSE quantity END) AS total_pieces FROM stock GROUP BY warehouse_id");
        input.put("columns", List.of(Map.of("name", "warehouse_id", "label", "warehouse_id"), Map.of("name", "total_pieces", "label", "total_pieces")));
        input.put("sampleRows", List.of(List.of(1, 120)));
        var converted = client.inspect(prompt, input);
        System.out.println("明确换算汇总检查=" + converted);
        assertThat(converted.decision()).isEqualTo("PASS");

        input.put("requirement", Map.of("goal", "查看各仓库库存数量，按适用单位汇总", "dataRequirement", "FULL", "grain", "每个仓库每种单位一行"));
        input.put("knowledge", "quantity 是业务数量，unit_snapshot 是对应单位，存在箱、卷等不同单位，不存在换算关系；相同单位的库存数量可合计。");
        input.put("previousSql", "SELECT warehouse_id, SUM(quantity) AS total_qty FROM stock GROUP BY warehouse_id");
        input.put("feedback", incorrect.message()); input.put("pageSize", 200); input.put("maxTotalRows", 5000);
        var corrected = client.generate(SqlModelClient.loadPrompt("prompts/sql/generate.md"), input);
        System.out.println("检查反馈后的重新生成=" + corrected);
        assertThat(corrected).isInstanceOf(SqlQueryOutput.class);
        assertThat(((SqlQueryOutput) corrected).sql()).containsIgnoringCase("unit_snapshot");
    }

    @Test
    void verifiesMissingAndExplicitWarehouseScopes() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        String thinking = settings.getProperty("erp.ai.model.enable-thinking");
        Boolean enableThinking = thinking == null || thinking.isBlank() ? null : Boolean.valueOf(thinking.trim());
        var model = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.flash"),
                0.2, Duration.ofSeconds(90), enableThinking);
        var client = new SqlModelClient(SqlGraphConfiguration.sqlModel(model), new ObjectMapper());
        String schema = """
                【DB_ID】 erp
                # Table: ai_stock_probe, 库存
                # 软删除：该表不需要软删除过滤，不要添加 deleted 条件
                [(id:BIGINT, 商品编号, Primary Key), (warehouse_id:BIGINT, 仓库编号),
                 (quantity:DECIMAL, 库存数量), (unit_snapshot:VARCHAR, 库存单位)]
                # Indexes: idx_wh(warehouse_id)
                """;
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("requirement", Map.of("goal", "查询指定仓库的商品库存数量及库存单位，按每个商品一行返回商品编号、仓库编号、库存数量、库存单位快照",
                "dataRequirement", "FULL", "grain", "每个商品一行", "expectedFields", List.of("商品编号", "仓库编号", "库存数量", "库存单位快照")));
        input.put("supplements", List.of());
        input.put("schema", schema);
        input.put("knowledge", "quantity为库存数量，unit_snapshot为库存单位；不同单位不可直接合计。");
        input.put("pageSize", 200); input.put("maxTotalRows", 5000);
        input.put("currentDate", "2026-10-29"); input.put("timeZone", "Asia/Shanghai");
        input.put("previousSql", ""); input.put("feedback", "");
        String generatorPrompt = SqlModelClient.loadPrompt("prompts/sql/generate.md");
        var missing = client.generate(generatorPrompt, input);
        System.out.println("SQL生成：指定仓库未说明条件 -> " + missing);
        assertThat(missing).isInstanceOf(SqlNeedInfoOutput.class);

        input.put("requirement", Map.of("goal", "查询全部仓库的商品库存及单位", "dataRequirement", "FULL", "grain", "每条库存记录一行"));
        var all = client.generate(generatorPrompt, input);
        System.out.println("SQL生成：明确全部仓库 -> " + all);
        assertThat(all).isInstanceOf(SqlQueryOutput.class);

        input.put("requirement", Map.of("goal", "查询指定仓库的商品库存及单位，按商品返回", "dataRequirement", "FULL"));
        input.put("sql", "SELECT id AS product_id, warehouse_id, quantity, unit_snapshot FROM ai_stock_probe");
        input.put("needFullData", true); input.put("limitations", ""); input.put("rowCount", 3);
        input.put("columns", List.of(Map.of("name", "id", "label", "product_id"), Map.of("name", "warehouse_id", "label", "warehouse_id"),
                Map.of("name", "quantity", "label", "quantity"), Map.of("name", "unit_snapshot", "label", "unit_snapshot")));
        input.put("sampleRows", List.of(List.of(1, 1, 30.25, "千克"), List.of(2, 2, 8.5, "件")));
        var inspection = client.inspect(SqlModelClient.loadPrompt("prompts/sql/inspect.md"), input);
        System.out.println("结果检查：指定仓库但 SQL 查询全部 -> " + inspection);
        assertThat(inspection.decision()).isIn("RETRY", "FAILED");
        if ("RETRY".equals(inspection.decision())) {
            // 检查模型允许先退回生成节点；缺少真实仓库时生成节点仍应要求补充。
            input.put("previousSql", input.get("sql"));
            input.put("feedback", inspection.message());
            for (String key : List.of("sql", "needFullData", "limitations", "rowCount", "columns", "sampleRows")) input.remove(key);
            var revised = client.generate(generatorPrompt, input);
            System.out.println("检查反馈后的 SQL 生成 -> " + revised);
            assertThat(revised).isInstanceOf(SqlNeedInfoOutput.class);
        }

        // 验证真实模型可以按新格式说明包含中文别名的结果列。
        input.put("requirement", Map.of("goal", "查询全部仓库的商品库存及单位", "dataRequirement", "FULL", "grain", "每条库存记录一行"));
        input.put("sql", "SELECT id AS 商品编号, warehouse_id AS 仓库编号, quantity AS 库存数量, unit_snapshot AS 单位 FROM ai_stock_probe");
        input.put("needFullData", true);
        input.put("completed", true);
        input.put("limitations", "");
        input.put("rowCount", 1);
        input.put("columns", List.of(Map.of("name", "id", "label", "商品编号"), Map.of("name", "warehouse_id", "label", "仓库编号"),
                Map.of("name", "quantity", "label", "库存数量"), Map.of("name", "unit_snapshot", "label", "单位")));
        input.put("sampleRows", List.of(List.of(1, 1, 30.25, "千克")));
        var valid = client.inspect(SqlModelClient.loadPrompt("prompts/sql/inspect.md"), input);
        System.out.println("结果检查：中文别名和新字段格式 -> " + valid);
        assertThat(valid.decision()).isEqualTo("PASS");
        assertThat(valid.dataDescription().fields()).hasSize(4);
        for (int i = 0; i < 4; i++) assertThat(valid.dataDescription().fields().get(i).index()).isEqualTo(i);
    }
}
