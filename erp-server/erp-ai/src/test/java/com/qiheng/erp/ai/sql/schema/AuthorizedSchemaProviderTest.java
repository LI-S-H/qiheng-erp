package com.qiheng.erp.ai.sql.schema;

import com.qiheng.erp.ai.domain.entity.AiDataTable;
import com.qiheng.erp.ai.domain.entity.AiDataTableColumn;
import com.qiheng.erp.ai.domain.entity.AiDataTableRelation;
import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;
import com.qiheng.erp.ai.mapper.AiDataTableColumnMapper;
import com.qiheng.erp.ai.mapper.AiDataTableMapper;
import com.qiheng.erp.ai.mapper.AiDataTableRelationMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 可见范围组装单测：权限码到模块的映射、动作口径、关系两端过滤、缓存命中、
 * 以及给 SQL 守卫用的结构化清单。
 *
 * @author Li
 * @since 2026-10-21
 */
class AuthorizedSchemaProviderTest {

    private final AiDataTableMapper tableMapper = mock(AiDataTableMapper.class);
    private final AiDataTableColumnMapper columnMapper = mock(AiDataTableColumnMapper.class);
    private final AiDataTableRelationMapper relationMapper = mock(AiDataTableRelationMapper.class);
    private final SchemaCache cache = new SchemaCache(300);

    private AuthorizedSchemaProvider provider() {
        return new AuthorizedSchemaProvider(tableMapper, columnMapper, relationMapper,
                new SchemaRenderer(), cache);
    }

    @Test
    void permissionCodesAreResolvedToModulePrefixes() {
        when(tableMapper.selectEnabledByModules(List.of("product", "warehouse"))).thenReturn(List.of());
        when(columnMapper.selectEnabledByTables(anyList())).thenReturn(List.of());
        when(relationMapper.selectEnabled()).thenReturn(List.of());

        provider().provide(List.of("product:query", "warehouse:manage", "product:create"));

        verify(tableMapper).selectEnabledByModules(List.of("product", "warehouse"));
    }

    @Test
    void createOnlyPermissionDoesNotGrantTableVisibility() {
        // ERP 列表页要的是 query 权限；只有 create 的用户看不到列表页，
        // 就不该让 AI 替他查这个模块的数据。
        AuthorizedSchemaSnapshot snapshot = provider().provideSnapshot(List.of("product:create"));

        assertThat(snapshot.tables()).isEmpty();
        verify(tableMapper, never()).selectEnabledByModules(anyList());
    }

    @Test
    void userWithoutAnyModuleGetsEmptySchemaWithoutQueryingTables() {
        String schema = provider().provide(List.of());

        assertThat(schema).isEmpty();
        verify(tableMapper, never()).selectEnabledByModules(anyList());
    }

    @Test
    void adminPermissionCodeSeesAllEnabledTables() {
        when(tableMapper.selectEnabledAll()).thenReturn(List.of());
        when(columnMapper.selectEnabledByTables(anyList())).thenReturn(List.of());
        when(relationMapper.selectEnabled()).thenReturn(List.of());

        provider().provide(List.of("*"));

        verify(tableMapper).selectEnabledAll();
        verify(tableMapper, never()).selectEnabledByModules(anyList());
    }

    @Test
    void relationWhoseTargetTableIsInvisibleIsNotRendered() {
        AiDataTable visible = table("purchase_order_item", "采购订单明细表");
        AiDataTableRelation relation = relation("purchase_order_item", "purchase_order_id",
                "purchase_order", "id");
        when(tableMapper.selectEnabledByModules(List.of("purchase"))).thenReturn(List.of(visible));
        when(columnMapper.selectEnabledByTables(anyList())).thenReturn(List.of());
        when(relationMapper.selectEnabled()).thenReturn(List.of(relation));

        String schema = provider().provide(List.of("purchase:query"));

        // purchase_order 不可见 → 这条关系不能给模型，否则它会去 JOIN 不存在的表
        assertThat(schema).doesNotContain("【Foreign keys】");
    }

    @Test
    void secondCallHitsCacheAndSkipsTableQuery() {
        AiDataTable visible = table("product", "产品表");
        when(tableMapper.selectEnabledByModules(List.of("product"))).thenReturn(List.of(visible));
        when(columnMapper.selectEnabledByTables(anyList())).thenReturn(List.of());
        when(relationMapper.selectEnabled()).thenReturn(List.of());

        AuthorizedSchemaProvider provider = provider();
        provider.provide(List.of("product:query"));
        provider.provide(List.of("product:query"));

        verify(tableMapper, times(1)).selectEnabledByModules(List.of("product"));
    }

    @Test
    void snapshotCarriesVisibleTablesAndColumns() {
        AiDataTable visible = table("product", "产品表");
        when(tableMapper.selectEnabledByModules(List.of("product"))).thenReturn(List.of(visible));
        when(columnMapper.selectEnabledByTables(anyList())).thenReturn(List.of(
                column("product", "id", "BIGINT", false, true),
                column("product", "name", "VARCHAR", true, false)));
        when(relationMapper.selectEnabled()).thenReturn(List.of());
        when(columnMapper.selectByTablesAndColumn(anyList(), eq("deleted"))).thenReturn(List.of());
        when(columnMapper.selectPrimaryKeys(anyList())).thenReturn(List.of(
                column("product", "id", "BIGINT", false, true)));

        AuthorizedSchemaSnapshot snapshot = provider().provideSnapshot(List.of("product:query"));

        assertThat(snapshot.tableNames()).containsExactly("product");
        assertThat(snapshot.hasColumn("product", "id")).isTrue();
        assertThat(snapshot.hasColumn("product", "remark")).isFalse();
        assertThat(snapshot.findColumn("product", "name").nullable()).isTrue();
        assertThat(snapshot.primaryKeyOf("product")).isEqualTo("id");
        assertThat(snapshot.fingerprint()).isNotBlank();
    }

    @Test
    void tableWithLogicalDeleteColumnIsMarked() {
        AiDataTable visible = table("purchase_order", "采购订单");
        when(tableMapper.selectEnabledByModules(List.of("purchase"))).thenReturn(List.of(visible));
        when(columnMapper.selectEnabledByTables(anyList())).thenReturn(List.of(
                column("purchase_order", "id", "BIGINT", false, true)));
        when(relationMapper.selectEnabled()).thenReturn(List.of());
        // 软删除列存在（即使这一列本身被屏蔽，事实也一样）
        when(columnMapper.selectByTablesAndColumn(anyList(), eq("deleted")))
                .thenReturn(List.of(column("purchase_order", "deleted", "TINYINT", false, false)));
        when(columnMapper.selectPrimaryKeys(anyList())).thenReturn(List.of(
                column("purchase_order", "id", "BIGINT", false, true)));

        AuthorizedSchemaSnapshot snapshot = provider().provideSnapshot(List.of("purchase:query"));

        assertThat(snapshot.hasLogicalDelete("purchase_order")).isTrue();
        assertThat(snapshot.hasLogicalDelete("purchase_order_item")).isFalse();
        assertThat(snapshot.schemaText()).contains("软删除：所需过滤字段不可用，无法安全查询该表");
        assertThat(snapshot.schemaText()).doesNotContain("deleted = 0");
    }

    @Test
    void rendersSoftDeleteRulesUsingPhysicalFactsAndVisibleColumns() {
        var plain = table("stock", "库存");
        var deleted = table("product", "商品");
        when(tableMapper.selectEnabledAll()).thenReturn(List.of(plain, deleted));
        when(columnMapper.selectEnabledByTables(anyList())).thenReturn(List.of(
                column("stock", "id", "BIGINT", false, true),
                column("product", "id", "BIGINT", false, true),
                column("product", "deleted", "TINYINT", false, false)));
        when(relationMapper.selectEnabled()).thenReturn(List.of());
        when(columnMapper.selectByTablesAndColumn(anyList(), eq("deleted")))
                .thenReturn(List.of(column("product", "deleted", "TINYINT", false, false)));
        when(columnMapper.selectPrimaryKeys(anyList())).thenReturn(List.of());

        String text = provider().provideFreshSnapshot(List.of("*")).schemaText();
        assertThat(text).contains("# Table: stock, 库存\n# 软删除：该表不需要软删除过滤，不要添加 deleted 条件")
                .contains("# Table: product, 商品\n# 软删除：查询时必须使用 deleted = 0");
    }

    private AiDataTable table(String name, String businessName) {
        AiDataTable table = new AiDataTable();
        table.setTableName(name);
        table.setBusinessName(businessName);
        table.setModuleCode("product");
        table.setStatus(1);
        return table;
    }

    private AiDataTableColumn column(String tableName, String columnName, String type,
                                     boolean nullable, boolean primaryKey) {
        AiDataTableColumn column = new AiDataTableColumn();
        column.setTableName(tableName);
        column.setColumnName(columnName);
        column.setColumnType(type);
        column.setNullable(nullable ? 1 : 0);
        column.setPrimaryKey(primaryKey ? 1 : 0);
        column.setStatus(1);
        return column;
    }

    private AiDataTableRelation relation(String fromTable, String fromColumn, String toTable, String toColumn) {
        AiDataTableRelation relation = new AiDataTableRelation();
        relation.setFromTable(fromTable);
        relation.setFromColumn(fromColumn);
        relation.setToTable(toTable);
        relation.setToColumn(toColumn);
        relation.setCardinality("N:1");
        relation.setDescription("所属采购订单");
        relation.setStatus(1);
        return relation;
    }
}
