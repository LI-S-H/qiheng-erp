package com.qiheng.erp.ai.sql.registry;

import com.qiheng.erp.ai.mapper.AiDataTableColumnMapper;
import com.qiheng.erp.ai.mapper.AiDataTableMapper;
import com.qiheng.erp.ai.mapper.AiDataTableRelationMapper;
import com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

/** 从 ERP 元数据只读生成业务图，便于检查真实内容和规模。 */
@SpringJUnitConfig(SchemaProviderMysqlIntegrationTest.TestConfig.class)
@EnabledIfEnvironmentVariable(named = "ERP_BUSINESS_GRAPH_TEST", matches = "true")
class BusinessGraphReadOnlyIT {
    @Autowired AiDataTableMapper tables;
    @Autowired AiDataTableColumnMapper columns;
    @Autowired AiDataTableRelationMapper relations;

    @Test
    void rendersRegisteredBusinessGraph() throws Exception {
        String text = new BusinessGraphProvider(tables, columns, relations).render();
        assertThat(text).contains("ERP", "实体", "关系");
        Path output = Path.of("..", "target", "business-graph-reference.txt");
        Files.createDirectories(output.getParent());
        Files.writeString(output, text);
        System.out.printf("业务图谱：实体=%d 关系=%d 字符=%d 输出=%s%n",
                text.lines().filter(line -> line.startsWith("实体：")).count(),
                text.lines().filter(line -> line.startsWith("- ") && line.contains(" → ")).count(),
                text.length(), output.toAbsolutePath().normalize());
    }
}
