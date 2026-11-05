package com.qiheng.erp.ai.knowledge.graph;

import com.qiheng.erp.ai.domain.entity.AiDataTableColumn;
import com.qiheng.erp.ai.mapper.AiDataTableColumnMapper;
import com.qiheng.erp.ai.mapper.AiDataTableMapper;
import com.qiheng.erp.ai.mapper.AiDataTableRelationMapper;
import org.springframework.stereotype.Component;
import java.util.HashSet;
import java.util.Set;

/** 从启用的元数据生成全系统业务实体、字段与关系参考。 */
@Component
public class BusinessGraphProvider {
    private final AiDataTableMapper tables;
    private final AiDataTableColumnMapper columns;
    private final AiDataTableRelationMapper relations;

    public BusinessGraphProvider(AiDataTableMapper tables, AiDataTableColumnMapper columns, AiDataTableRelationMapper relations) {
        this.tables = tables;
        this.columns = columns;
        this.relations = relations;
    }

    /** 图谱只描述登记内容；实际数据访问以当前用户授权为准。 */
    public String render() {
        var registered = tables.selectEnabledAll();
        StringBuilder text = new StringBuilder("【ERP 业务系统内实体、字段及关系参考】\n仅用于理解和规划；字段与关系不代表实际数据或用户权限，查询以工具结果为准。未登记不代表不存在。\n");
        if (registered.isEmpty()) return text.append("当前没有登记的实体。\n").toString();
        var names = registered.stream().map(t -> t.getTableName()).toList();
        var visible = columns.selectEnabledByTables(names);
        Set<String> keys = new HashSet<>();
        for (AiDataTableColumn column : visible) keys.add(column.getTableName() + "." + column.getColumnName());
        for (var table : registered) {
            text.append("\n实体：").append(table.getBusinessName()).append("（").append(table.getTableName()).append("）\n");
            if (table.getDescription() != null && !table.getDescription().isBlank()) text.append("说明：").append(table.getDescription()).append('\n');
            for (var column : visible) {
                if (!table.getTableName().equals(column.getTableName())) continue;
                text.append("- ").append(column.getColumnName()).append("：");
                String description = column.getDescription();
                if (description == null || description.isBlank()) description = column.getBusinessName();
                text.append(description == null || description.isBlank() ? "未登记说明" : description).append('\n');
            }
        }
        text.append("\n实体关系（基数方向为来源表到目标表）：\n");
        for (var relation : relations.selectEnabled()) {
            String from = relation.getFromTable() + "." + relation.getFromColumn();
            String to = relation.getToTable() + "." + relation.getToColumn();
            // 两端字段均已启用时才展示，避免暴露关闭的字段。
            if (!keys.contains(from) || !keys.contains(to)) continue;
            text.append("- ").append(from).append(" → ").append(to);
            if (relation.getCardinality() != null && !relation.getCardinality().isBlank()) text.append(" [").append(relation.getCardinality()).append(']');
            if (relation.getDescription() != null && !relation.getDescription().isBlank()) text.append("：").append(relation.getDescription());
            text.append('\n');
        }
        return text.toString();
    }
}
