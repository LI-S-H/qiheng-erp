package com.qiheng.erp.ai.service.data.schema;

import com.qiheng.erp.ai.domain.entity.AiDataTable;
import com.qiheng.erp.ai.domain.entity.AiDataTableColumn;
import com.qiheng.erp.ai.domain.entity.AiDataTableRelation;
import com.qiheng.erp.ai.mapper.AiDataTableColumnMapper;
import com.qiheng.erp.ai.mapper.AiDataTableMapper;
import com.qiheng.erp.ai.mapper.AiDataTableRelationMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 可见范围组装单测：权限码到模块的映射、关系两端过滤、缓存命中。
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

    private AiDataTable table(String name, String businessName) {
        AiDataTable table = new AiDataTable();
        table.setTableName(name);
        table.setBusinessName(businessName);
        table.setModuleCode("product");
        table.setStatus(1);
        return table;
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
