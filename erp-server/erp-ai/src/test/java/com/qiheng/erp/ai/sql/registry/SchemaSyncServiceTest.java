package com.qiheng.erp.ai.sql.registry;

import com.qiheng.erp.ai.sql.registry.SchemaSyncService;
import com.qiheng.erp.ai.sql.registry.SchemaLibraryReader;
import com.qiheng.erp.ai.sql.registry.SchemaSyncReport;
import com.qiheng.erp.ai.domain.entity.AiDataTable;
import com.qiheng.erp.ai.domain.entity.AiDataTableColumn;
import com.qiheng.erp.ai.domain.entity.AiDataTableRelation;
import com.qiheng.erp.ai.domain.model.ColumnFacts;
import com.qiheng.erp.ai.domain.model.IndexFacts;
import com.qiheng.erp.ai.mapper.AiDataTableColumnMapper;
import com.qiheng.erp.ai.mapper.AiDataTableMapper;
import com.qiheng.erp.ai.mapper.AiDataTableRelationMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 结构同步单测：四种差异的处理与「不碰语义字段」这条硬约束。
 *
 * @author Li
 * @since 2026-10-21
 */
class SchemaSyncServiceTest {

    private final SchemaLibraryReader libraryReader = mock(SchemaLibraryReader.class);
    private final AiDataTableMapper tableMapper = mock(AiDataTableMapper.class);
    private final AiDataTableColumnMapper columnMapper = mock(AiDataTableColumnMapper.class);
    private final AiDataTableRelationMapper relationMapper = mock(AiDataTableRelationMapper.class);

    @Test
    void newColumnIsRegisteredButNotExposedToModel() {
        when(tableMapper.selectAllRegistered()).thenReturn(List.of(activeTable()));
        when(columnMapper.selectByTables(List.of("product"))).thenReturn(List.of());
        stubLibrary("product", column("product", "new_col", "BIGINT", 0, 0, 0));
        when(relationMapper.selectAllRegistered()).thenReturn(List.of());

        SchemaSyncReport report = service().sync();

        assertThat(report.insertedColumns()).isEqualTo(1);
        // 新列默认不下发：语义字段还空着，不能进模型视野
        ArgumentCaptor<AiDataTableColumn> captor = ArgumentCaptor.forClass(AiDataTableColumn.class);
        verify(columnMapper).insert(captor.capture());
        AiDataTableColumn inserted = captor.getValue();
        assertThat(inserted.getColumnName()).isEqualTo("new_col");
        assertThat(inserted.getStatus()).isZero();
        assertThat(inserted.getBusinessName()).isEmpty();
        assertThat(inserted.getDescription()).isEmpty();
    }

    @Test
    void existingColumnWithChangedTypeOnlyRefreshesStructureFields() {
        AiDataTableColumn registered = registeredColumn("product", "qty", "BIGINT", 1, 0, 0);
        when(tableMapper.selectAllRegistered()).thenReturn(List.of(activeTable()));
        when(columnMapper.selectByTables(List.of("product"))).thenReturn(List.of(registered));
        stubLibrary("product", column("product", "qty", "INT", 0, 0, 0));
        when(relationMapper.selectAllRegistered()).thenReturn(List.of());

        SchemaSyncReport report = service().sync();

        assertThat(report.updatedColumns()).isEqualTo(1);
        verify(columnMapper).updateStructure(eq(registered.getId()), eq("INT"), eq(0), eq(0), eq(0));
        // 语义字段不在更新语句里，登记里的业务名与口径一个字都不动
        verify(columnMapper, never()).updateById(any(AiDataTableColumn.class));
    }

    @Test
    void columnVanishedFromLibraryIsSoftDeleted() {
        AiDataTableColumn registered = registeredColumn("product", "gone_col", "BIGINT", 0, 0, 0);
        when(tableMapper.selectAllRegistered()).thenReturn(List.of(activeTable()));
        when(columnMapper.selectByTables(List.of("product"))).thenReturn(List.of(registered));
        stubLibrary("product", column("product", "other_col", "BIGINT", 0, 0, 0));
        when(relationMapper.selectAllRegistered()).thenReturn(List.of());
        when(columnMapper.selectIdentityIncludingDeleted("product", "gone_col")).thenReturn(null);

        SchemaSyncReport report = service().sync();

        assertThat(report.softDeletedColumns()).isEqualTo(1);
        verify(columnMapper).softDelete(registered.getId());
    }

    @Test
    void relationPointingToMissingColumnIsReported() {
        when(tableMapper.selectAllRegistered()).thenReturn(List.of(activeTable()));
        when(columnMapper.selectByTables(List.of("product"))).thenReturn(List.of());
        stubLibrary("product", column("product", "id", "BIGINT", 0, 1, 0));
        when(relationMapper.selectAllRegistered())
                .thenReturn(List.of(relation("product", "no_such_col", "product", "id")));

        SchemaSyncReport report = service().sync();

        assertThat(report.invalidRelations()).containsExactly("product.no_such_col=product.id");
    }

    @Test
    void syncIsIdempotentWhenNothingChanged() {
        AiDataTableColumn registered = registeredColumn("product", "id", "BIGINT", 0, 1, 0);
        when(tableMapper.selectAllRegistered()).thenReturn(List.of(activeTable()));
        when(columnMapper.selectByTables(List.of("product"))).thenReturn(List.of(registered));
        stubLibrary("product", column("product", "id", "BIGINT", 0, 1, 0));
        when(relationMapper.selectAllRegistered()).thenReturn(List.of());

        SchemaSyncReport report = service().sync();

        assertThat(report.changed()).isFalse();
        verify(columnMapper, never()).insert(any(AiDataTableColumn.class));
        verify(columnMapper, never()).updateStructure(any(), anyString(), any(), any(), any());
        verify(columnMapper, never()).softDelete(any());
        verify(tableMapper, never()).updateIndexDefs(any(), any());
    }

    @Test
    void tableWithoutAnyRegisteredColumnIsNotTouched() {
        when(tableMapper.selectAllRegistered()).thenReturn(List.of(activeTable()));
        when(columnMapper.selectByTables(List.of("product"))).thenReturn(List.of());
        stubLibrary("product");
        when(relationMapper.selectAllRegistered()).thenReturn(List.of());

        SchemaSyncReport report = service().sync();

        assertThat(report.insertedColumns()).isZero();
        verify(columnMapper, times(0)).insert(any(AiDataTableColumn.class));
    }

    private SchemaSyncService service() {
        return new SchemaSyncService(libraryReader, tableMapper, columnMapper, relationMapper);
    }

    /**
     * 让库结构读取返回给定的列事实。
     */
    private void stubLibrary(String table, ColumnFacts... columns) {
        when(libraryReader.readColumns(List.of(table)))
                .thenReturn(Map.of(table, List.of(columns)));
        when(libraryReader.readIndexes(List.of(table)))
                .thenReturn(Map.of(table, List.<IndexFacts>of()));
    }

    private ColumnFacts column(String table, String name, String type, int nullable, int primaryKey, int uniqueKey) {
        return new ColumnFacts(table, name, type, nullable, primaryKey, uniqueKey);
    }

    private AiDataTable activeTable() {
        AiDataTable table = new AiDataTable();
        table.setId(1L);
        table.setTableName("product");
        table.setBusinessName("产品表");
        table.setModuleCode("product");
        table.setStatus(1);
        table.setDeleted(0);
        table.setSortOrder(10);
        return table;
    }

    private AiDataTableColumn registeredColumn(String table, String column, String type, int nullable,
                                                int primaryKey, int uniqueKey) {
        AiDataTableColumn entity = new AiDataTableColumn();
        entity.setId(100L);
        entity.setTableName(table);
        entity.setColumnName(column);
        entity.setColumnType(type);
        entity.setNullable(nullable);
        entity.setPrimaryKey(primaryKey);
        entity.setUniqueKey(uniqueKey);
        entity.setBusinessName("人工维护的业务名");
        entity.setDescription("人工维护的口径说明");
        entity.setStatus(1);
        entity.setDeleted(0);
        return entity;
    }

    private AiDataTableRelation relation(String fromTable, String fromColumn, String toTable, String toColumn) {
        AiDataTableRelation relation = new AiDataTableRelation();
        relation.setFromTable(fromTable);
        relation.setFromColumn(fromColumn);
        relation.setToTable(toTable);
        relation.setToColumn(toColumn);
        relation.setCardinality("N:1");
        relation.setStatus(1);
        relation.setDeleted(0);
        return relation;
    }
}
