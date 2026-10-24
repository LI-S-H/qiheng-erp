package com.qiheng.erp.ai.service.data.registry;

import java.util.List;

/**
 * 结构同步结果：本次同步做了什么、发现了什么问题。
 *
 * @param insertedColumns   新登记的列数
 * @param updatedColumns    结构字段被刷新的列数
 * @param softDeletedColumns 库中已不存在、被软删登记的列数
 * @param updatedIndexes    索引定义被刷新的表数
 * @param invalidRelations  指向已不存在列的失效关系
 * @author Li
 * @since 2026-10-21
 */
public record SchemaSyncReport(int insertedColumns, int updatedColumns, int softDeletedColumns,
                               int updatedIndexes, List<String> invalidRelations) {

    public SchemaSyncReport {
        invalidRelations = invalidRelations == null ? List.of() : List.copyOf(invalidRelations);
    }

    /**
     * 没有任何变更。
     */
    public static SchemaSyncReport unchanged() {
        return new SchemaSyncReport(0, 0, 0, 0, List.of());
    }

    /**
     * 本次同步是否有实际变更。
     */
    public boolean changed() {
        return insertedColumns > 0 || updatedColumns > 0 || softDeletedColumns > 0 || updatedIndexes > 0;
    }
}
