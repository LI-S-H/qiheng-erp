package com.qiheng.erp.ai.knowledge.graph;

import com.qiheng.erp.ai.domain.entity.AiDataTable;
import com.qiheng.erp.ai.domain.entity.AiDataTableColumn;
import com.qiheng.erp.ai.domain.entity.AiDataTableRelation;
import com.qiheng.erp.ai.mapper.AiDataTableMapper;
import com.qiheng.erp.ai.mapper.AiDataTableColumnMapper;
import com.qiheng.erp.ai.mapper.AiDataTableRelationMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class BusinessGraphProviderTest {
    @Test void rendersFieldsAndVisibleRelationsWithoutHiddenColumns() {
        var tables = mock(AiDataTableMapper.class);
        var columns = mock(AiDataTableColumnMapper.class);
        var relations = mock(AiDataTableRelationMapper.class);
        when(tables.selectEnabledAll()).thenReturn(List.of(
                new AiDataTable().setTableName("warehouse").setBusinessName("仓库"),
                new AiDataTable().setTableName("stock").setBusinessName("库存")));
        when(columns.selectEnabledByTables(List.of("warehouse", "stock"))).thenReturn(List.of(
                new AiDataTableColumn().setTableName("warehouse").setColumnName("id").setBusinessName("仓库编号"),
                new AiDataTableColumn().setTableName("stock").setColumnName("warehouse_id").setDescription("所属仓库")));
        when(relations.selectEnabled()).thenReturn(List.of(
                new AiDataTableRelation().setFromTable("stock").setFromColumn("warehouse_id").setToTable("warehouse").setToColumn("id").setCardinality("N:1").setDescription("库存属于仓库"),
                new AiDataTableRelation().setFromTable("stock").setFromColumn("secret").setToTable("warehouse").setToColumn("id")));
        String output = new BusinessGraphProvider(tables, columns, relations).render();
        assertThat(output).contains("ERP 业务系统内实体、字段及关系参考", "warehouse_id：所属仓库", "stock.warehouse_id → warehouse.id [N:1]：库存属于仓库").doesNotContain("secret");
    }
}
