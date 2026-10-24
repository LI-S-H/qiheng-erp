package com.qiheng.erp.ai.service.data.registry;

import com.qiheng.erp.ai.service.data.schema.AuthorizedSchemaProvider;
import com.qiheng.erp.ai.service.data.schema.SchemaRenderer;
import com.qiheng.erp.ai.service.data.schema.SchemaCache;
import com.qiheng.erp.ai.service.data.registry.SchemaSyncService;
import com.qiheng.erp.ai.service.data.registry.SchemaLibraryReader;
import com.qiheng.erp.ai.service.data.registry.SchemaSyncReport;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.github.yulichang.autoconfigure.MybatisPlusJoinAutoConfiguration;
import com.qiheng.erp.ai.domain.entity.AiDataTable;
import com.qiheng.erp.ai.domain.entity.AiDataTableColumn;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 受控数据底座真库集成测试。
 *
 * <p>显式使用 {@code -Dai.mysql.it=true} 启用；连接参数可用系统属性覆盖：
 * {@code ai.mysql.url}、{@code ai.mysql.username}、{@code ai.mysql.password}。</p>
 *
 * <p>验证两件在单元测试里验不了的事：同步程序对真实库是幂等的、
 * 且不会误删登记；以及按角色组装出的 Schema 真的做了关系两端过滤。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
@SpringJUnitConfig(SchemaProviderMysqlIntegrationTest.TestConfig.class)
@EnabledIfSystemProperty(named = "ai.mysql.it", matches = "true")
class SchemaProviderMysqlIntegrationTest {

    @Autowired
    private SchemaSyncService syncService;

    @Autowired
    private com.qiheng.erp.ai.mapper.AiDataTableMapper tableMapper;

    @Autowired
    private com.qiheng.erp.ai.mapper.AiDataTableColumnMapper columnMapper;

    @Autowired
    private AuthorizedSchemaProvider provider;

    @Test
    void syncAgainstRealLibraryIsIdempotentAndKeepsManualSemantics() {
        SchemaSyncReport first = syncService.sync();
        SchemaSyncReport second = syncService.sync();

        // 第二次跑不应有变更：结构已经对齐，登记与语义都没被动过
        assertThat(second.changed()).isFalse();
        assertThat(first.insertedColumns()).isZero();
        assertThat(first.softDeletedColumns()).isZero();
        assertThat(first.invalidRelations()).isEmpty();
    }

    @Test
    void manualSemanticsSurviveSync() {
        syncService.sync();

        Long manualColumns = jdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM ai_data_table_column WHERE business_name <> '' AND description <> ''",
                Long.class);
        assertThat(manualColumns).isPositive();
    }

    @Test
    void jsonColumnsSurviveRealReadPath() {
        // 自定义 @Select 不会自动套用 @TableField 的 typeHandler，JSON 列必须显式映射
        List<AiDataTable> tables = tableMapper.selectAllRegistered();
        assertThat(tables).isNotEmpty();
        AiDataTable product = tables.stream()
                .filter(table -> "product".equals(table.getTableName())).findFirst().orElseThrow();
        assertThat(product.getIndexDefs()).isNotNull();
        assertThat(product.getIndexDefs()).isNotEmpty();
        assertThat(product.getIndexDefs().get(0).name()).isNotBlank();

        List<AiDataTableColumn> columns = columnMapper.selectByTables(List.of("product"));
        AiDataTableColumn status = columns.stream()
                .filter(column -> "product".equals(column.getTableName()) && "status".equals(column.getColumnName()))
                .findFirst().orElseThrow();
        assertThat(status.getEnumValues()).isNotNull();
        assertThat(status.getEnumValues()).containsExactly("1", "0");
    }

    @Test
    void providerScopesTablesByPermissionModule() {
        String warehouse = provider.provide(List.of("warehouse:query"));
        String purchase = provider.provide(List.of("purchase:query"));

        assertThat(warehouse).contains("# Table: warehouse_stock");
        assertThat(warehouse).doesNotContain("# Table: purchase_order");
        assertThat(purchase).contains("# Table: purchase_order");
        assertThat(purchase).doesNotContain("# Table: warehouse_stock");
    }

    @Test
    void relationWithInvisibleTargetIsNotRendered() {
        // 只给 sales 模块：sales_order_item 可见但 product 不可见，
        // 这条跨模块关系就不能给模型，否则它会去 JOIN 看不到的表
        String schema = provider.provide(List.of("sales:query"));

        assertThat(schema).contains("# Table: sales_order_item");
        assertThat(schema).doesNotContain("# Table: product");
        assertThat(schema).doesNotContain("sales_order_item.product_id=product.id");
    }

    @Test
    void relationWithBothEndsVisibleIsRendered() {
        // sales 与 product 都可见时，跨模块关系要给
        String schema = provider.provide(List.of("sales:query", "product:query"));

        assertThat(schema).contains("sales_order_item.product_id=product.id");
    }

    @Test
    void adminSeesAllRegisteredTables() {
        String schema = provider.provide(List.of("*"));

        assertThat(schema).contains("# Table: product");
        assertThat(schema).contains("# Table: warehouse_stock");
        assertThat(schema).contains("【Foreign keys】");
    }

    @Test
    void visibleColumnsExcludeNoiseColumns() {
        String schema = provider.provide(List.of("*"));

        // version 与 remark 是噪声列，登记了但不下发
        assertThat(schema).doesNotContain("version:BIGINT");
        assertThat(schema).doesNotContain("remark:VARCHAR");
        assertThat(schema).contains("product_code:VARCHAR");
    }

    private JdbcTemplate jdbcTemplate() {
        return new JdbcTemplate(dataSource());
    }

    private DataSource dataSource() {
        return TestConfig.dataSourceInstance();
    }

    /**
     * 只装配受控数据底座需要的东西：数据源、MyBatis-Plus、三个服务。
     */
    @TestConfiguration
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, MybatisPlusAutoConfiguration.class,
            MybatisPlusJoinAutoConfiguration.class})
    @MapperScan("com.qiheng.erp.ai.mapper")
    @Import({SchemaLibraryReader.class, SchemaSyncService.class, SchemaRenderer.class,
            AuthorizedSchemaProvider.class})
    static class TestConfig {

        private static HikariDataSource instance;

        static DataSource dataSourceInstance() {
            return instance;
        }

        @Bean
        DataSource dataSource() {
            HikariDataSource source = new HikariDataSource();
            source.setJdbcUrl(System.getProperty("ai.mysql.url",
                    "jdbc:mysql://localhost:13307/erp?useUnicode=true&characterEncoding=utf8"
                            + "&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
            source.setUsername(System.getProperty("ai.mysql.username", "root"));
            source.setPassword(System.getProperty("ai.mysql.password", "123456"));
            source.setMaximumPoolSize(4);
            instance = source;
            return source;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SchemaCache schemaCache() {
            return new SchemaCache(300);
        }
    }
}
