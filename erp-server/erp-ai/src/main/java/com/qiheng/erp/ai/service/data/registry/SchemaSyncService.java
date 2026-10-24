package com.qiheng.erp.ai.service.data.registry;

import com.qiheng.erp.ai.domain.entity.AiDataTable;
import com.qiheng.erp.ai.domain.entity.AiDataTableColumn;
import com.qiheng.erp.ai.domain.entity.AiDataTableRelation;
import com.qiheng.erp.ai.domain.model.ColumnFacts;
import com.qiheng.erp.ai.domain.model.IndexDefinition;
import com.qiheng.erp.ai.domain.model.IndexFacts;
import com.qiheng.erp.ai.mapper.AiDataTableColumnMapper;
import com.qiheng.erp.ai.mapper.AiDataTableMapper;
import com.qiheng.erp.ai.mapper.AiDataTableRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 元数据表结构同步：把库里的真实结构刷新到登记表的结构字段上。
 *
 * <p>登记表的结构字段是快照，库结构会变——加列、改类型、加索引。人工不可能每次都记得同步，
 * 所以启动时跑一次，使真实结构与登记对齐。</p>
 *
 * <p><b>三条硬约束：</b></p>
 * <ol>
 *   <li><b>绝不碰语义字段</b>——{@code business_name}、{@code description}、{@code enum_values}
 *       是人工维护的业务知识，同步程序只更新结构字段；</li>
 *   <li><b>幂等</b>——跑两次结果完全一样；</li>
 *   <li><b>表不自动登记</b>——登记表是授权范围，新增业务表必须人工登记；
 *       但列跟着表自动登记，且新列默认 {@code status=0} 不下发给模型，
 *       等人工补齐业务名与口径说明后再放开。</li>
 * </ol>
 *
 * @author Li
 * @since 2026-10-21
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SchemaSyncService {

    private final SchemaLibraryReader libraryReader;
    private final AiDataTableMapper tableMapper;
    private final AiDataTableColumnMapper columnMapper;
    private final AiDataTableRelationMapper relationMapper;

    /**
     * 执行一次同步。
     *
     * @return 同步结果
     */
    public SchemaSyncReport sync() {
        //  ai_data_table 查出所有已启用（status=1）的表
        List<AiDataTable> active = tableMapper.selectAllRegistered().stream()
                .filter(table -> Objects.equals(table.getStatus(), 1))
                .toList();
        if (active.isEmpty()) {
            return SchemaSyncReport.unchanged();
        }
        // 过滤出全部的表名
        List<String> tableNames = active.stream().map(AiDataTable::getTableName).toList();
        // 从登记库读取所有表的列和索引
        Map<String, List<ColumnFacts>> facts = libraryReader.readColumns(tableNames);
        Map<String, List<IndexFacts>> indexes = libraryReader.readIndexes(tableNames);
        // 应用唯一键约束
        facts = applyUniqueKeys(facts, indexes);

        int inserted = 0;
        int updated = 0;
        int softDeleted = 0;
        int updatedIndexes = 0;

        for (AiDataTable table : active) {
            String tableName = table.getTableName();
            // 真实库里的列定义
            List<ColumnFacts> tableFacts = facts.getOrDefault(tableName, List.of());
            if (tableFacts.isEmpty()) {
                // 登记了但库里读不到这一张表：不新增也不删除登记，只记录问题
                log.warn("登记的数据表在库中不可见，跳过同步 table={}", tableName);
                continue;
            }
            // 同步索引定义
            if (syncIndexes(table, indexes.getOrDefault(tableName, List.of()))) {
                updatedIndexes++;
            }
            // 查询登记表的列定义
            List<AiDataTableColumn> registered = columnMapper.selectByTables(List.of(tableName));
            // 把登记表的列定义按列名索引
            Map<String, AiDataTableColumn> registeredIndex = indexColumns(registered);
            for (ColumnFacts fact : tableFacts) {
                AiDataTableColumn known = registeredIndex.get(key(tableName, fact.columnName()));
                if (known == null) {
                    if (registerColumn(tableName, fact)) {
                        //登记表里没有这列 → 新增登记
                        inserted++;
                    }
                } else if (refreshStructure(known, fact)) {
                    //登记表有这列，但结构有变化 → 更新登记
                    updated++;
                }
            }
            softDeleted += softDeleteVanished(tableName, tableFacts, registeredIndex);
        }
        // 检查表间关系是否有效
        List<String> invalidRelations = findInvalidRelations(facts);
        if (!invalidRelations.isEmpty()) {
            log.warn("表间关系指向了不存在的列，需人工修正 relations={}", invalidRelations);
        }
        return new SchemaSyncReport(inserted, updated, softDeleted, updatedIndexes, invalidRelations);
    }

    /**
     * 新列登记：结构字段按库里的事实填，语义字段留空，默认不下发给模型。
     *
     * <p>语义字段留空是有意为之——同步程序只知道「有这么一列」，不知道它是什么业务含义。
     * 空描述的列进 Schema 只会让模型误用，所以先挡在下发之外。</p>
     *
     * <p>列被删掉又加回来时复用旧登记：直接插入会撞唯一键。</p>
     */
    private boolean registerColumn(String tableName, ColumnFacts fact) {
        AiDataTableColumn identity = columnMapper.selectIdentityIncludingDeleted(tableName, fact.columnName());
        if (identity != null && Objects.equals(identity.getDeleted(), 1)) {
            columnMapper.revive(identity.getId(), fact.columnType(), fact.nullable(),
                    fact.primaryKey(), fact.uniqueKey(), 0);
            log.info("列曾被删除后重新出现，已复活登记并保持不下发 table={} column={}",
                    tableName, fact.columnName());
            return false;
        }
        AiDataTableColumn entity = new AiDataTableColumn()
                .setTableName(tableName)
                .setColumnName(fact.columnName())
                .setColumnType(fact.columnType())
                .setNullable(fact.nullable())
                .setPrimaryKey(fact.primaryKey())
                .setUniqueKey(fact.uniqueKey())
                .setBusinessName("")
                .setDescription("")
                .setEnumValues(List.of())
                .setStatus(0)
                .setSortOrder(0);
        columnMapper.insert(entity);
        log.info("发现新列，已登记但默认不下发给模型 table={} column={} type={}",
                tableName, fact.columnName(), fact.columnType());
        return true;
    }

    /**
     * 刷新结构字段；返回是否真的变了。
     */
    private boolean refreshStructure(AiDataTableColumn registered, ColumnFacts fact) {
        boolean same = Objects.equals(registered.getColumnType(), fact.columnType())
                && Objects.equals(registered.getNullable(), fact.nullable())
                && Objects.equals(registered.getPrimaryKey(), fact.primaryKey())
                && Objects.equals(registered.getUniqueKey(), fact.uniqueKey());
        if (same) {
            return false;
        }
        columnMapper.updateStructure(registered.getId(), fact.columnType(), fact.nullable(),
                fact.primaryKey(), fact.uniqueKey());
        log.info("列结构变化已刷新 table={} column={}", fact.tableName(), fact.columnName());
        return true;
    }

    /**
     * 库中已不存在的列做软删登记，保留历史不物理删除。
     */
    private int softDeleteVanished(String tableName, List<ColumnFacts> facts,
                                   Map<String, AiDataTableColumn> registeredColumns) {
        // 收集库里真实存在的列名
        Set<String> existing = new LinkedHashSet<>();
        for (ColumnFacts fact : facts) {
            existing.add(fact.columnName());
        }
        int count = 0;
        // 遍历登记表里的列
        for (Map.Entry<String, AiDataTableColumn> entry : registeredColumns.entrySet()) {
            AiDataTableColumn column = entry.getValue();
            if (!tableName.equals(column.getTableName())) {
                continue;
            }
            if (Objects.equals(column.getDeleted(), 1) || existing.contains(column.getColumnName())) {
                continue;
            }
            // 登记表有这列，但库里不存在 → 软删登记
            columnMapper.softDelete(column.getId());
            log.info("列在库中已不存在，软删登记 table={} column={}", tableName, column.getColumnName());
            count++;
        }
        return count;
    }

    /**
     * 刷新索引定义；返回是否真的变了。
     */
    private boolean syncIndexes(AiDataTable table, List<IndexFacts> facts) {
        List<IndexDefinition> definitions = new ArrayList<>();
        for (IndexFacts fact : facts) {
            // 把真实索引事实转成 IndexDefinition 列表
            definitions.add(new IndexDefinition(fact.name(), fact.unique(), fact.columns()));
        }
        // 对比当前登记的索引定义
        List<IndexDefinition> current = table.getIndexDefs() == null ? List.of() : table.getIndexDefs();
        if (current.equals(definitions)) {
            return false;
        }
        // 不同就更新登记表的 index_defs 字段
        tableMapper.updateIndexDefs(table.getId(), definitions);
        log.info("索引定义变化已刷新 table={}", table.getTableName());
        return true;
    }

    /**
     * 把「单列唯一索引」的列标成唯一键。
     *
     * <p>只有单列唯一索引才说明这一列本身取值唯一。复合唯一索引里的列不标——
     * 模型据此判断 JOIN key 是否唯一、要不要加 DISTINCT，标错会写出多余或错误的 SQL。</p>
     */
    private Map<String, List<ColumnFacts>> applyUniqueKeys(Map<String, List<ColumnFacts>> facts,
                                                           Map<String, List<IndexFacts>> indexes) {
        Map<String, List<ColumnFacts>> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<ColumnFacts>> entry : facts.entrySet()) {
            // 先收集这张表的"单列唯一索引"涉及的列
            Set<String> uniqueColumns = new LinkedHashSet<>();
            for (IndexFacts index : indexes.getOrDefault(entry.getKey(), List.of())) {
                if (index.unique() && index.columns().size() == 1) {
                    uniqueColumns.add(index.columns().get(0).toLowerCase());
                }
            }
            // 重建 ColumnFacts 列表，把命中的列 uniqueKey 设为 1
            List<ColumnFacts> rebuilt = new ArrayList<>();
            for (ColumnFacts fact : entry.getValue()) {
                int uniqueKey = uniqueColumns.contains(fact.columnName().toLowerCase()) ? 1 : 0;
                rebuilt.add(new ColumnFacts(fact.tableName(), fact.columnName(), fact.columnType(),
                        fact.nullable(), fact.primaryKey(), uniqueKey));
            }
            result.put(entry.getKey(), rebuilt);
        }
        return result;
    }

    /**
     * 找出指向已不存在列的关系；这些关系需要人工修正。
     */
    private List<String> findInvalidRelations(Map<String, List<ColumnFacts>> facts) {
        // 收集所有真实存在的"表名.列名"
        Set<String> existing = new LinkedHashSet<>();
        for (List<ColumnFacts> tableFacts : facts.values()) {
            for (ColumnFacts fact : tableFacts) {
                existing.add(key(fact.tableName(), fact.columnName()));
            }
        }
        // 遍历登记表里的关系
        List<String> invalid = new ArrayList<>();
        for (AiDataTableRelation relation : relationMapper.selectAllRegistered()) {
            boolean fromMissing = !existing.contains(key(relation.getFromTable(), relation.getFromColumn()));
            boolean toMissing = !existing.contains(key(relation.getToTable(), relation.getToColumn()));
            if (fromMissing || toMissing) {
                invalid.add(relation.getFromTable() + "." + relation.getFromColumn() + "="
                        + relation.getToTable() + "." + relation.getToColumn());
            }
        }
        return invalid;
    }

    /**
     * 把登记的列按「表名.列名」建索引，便于比对。
     */
    private Map<String, AiDataTableColumn> indexColumns(List<AiDataTableColumn> columns) {
        Map<String, AiDataTableColumn> result = new LinkedHashMap<>();
        for (AiDataTableColumn column : columns) {
            result.put(key(column.getTableName(), column.getColumnName()), column);
        }
        return result;
    }

    private String key(String tableName, String columnName) {
        return tableName.toLowerCase() + "." + columnName.toLowerCase();
    }
}
