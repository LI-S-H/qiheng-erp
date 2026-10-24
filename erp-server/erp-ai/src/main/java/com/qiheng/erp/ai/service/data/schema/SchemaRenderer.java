package com.qiheng.erp.ai.service.data.schema;

import com.qiheng.erp.ai.domain.entity.AiDataTable;
import com.qiheng.erp.ai.domain.entity.AiDataTableColumn;
import com.qiheng.erp.ai.domain.entity.AiDataTableRelation;
import com.qiheng.erp.ai.domain.model.IndexDefinition;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Schema 文本渲染：把登记数据渲染成模型直接可读的紧凑文本。
 *
 * <p>格式对齐 DataAgent 的做法（{@code # Table: 表名, 业务名} + 方括号列清单 + Foreign keys 块），
 * 但按 ERP 的实际情况做了四处调整，每一处都有具体理由：<p>
 *
 * <ol>
 *   <li><b>口径与列同行</b>——ERP 的数量与金额普遍放大 100 倍存储，单位的说明紧跟在列后面，
 *       模型不会漏掉「除以 100」；</li>
 *   <li><b>字母编码给 Examples，数字枚举不给</b>——{@code DRAFT/CONFIRMED} 这类编码光看描述
 *       说不清，必须列出来；而 {@code 状态：1启用，0禁用} 描述本身就是答案，
 *       再给一遍 {@code Examples: ["1","0"]} 是纯冗余；</li>
 *   <li><b>索引渲染成紧凑形式</b>——完整 JSON 太啰嗦，改成 {@code 名称(列,列)} 后
 *       Schema 文本短了两成多；</li>
 *   <li><b>主键标 Primary Key、唯一键标 Unique</b>——让模型知道 JOIN key 是否唯一，
 *       不用凭猜加 DISTINCT。</li>
 * </ol>
 *
 * 形成对应的 Schema 文本：
 * <p># Table: 表名, 业务名<p>
 * <p># 表描述（如果有且不等于业务名）<p>
 * <p>[<p>
 *   <p>(列1:类型, 描述, Primary Key/Unique, Examples: [...]),<p>
 *   <p>(列2:类型, 描述),<p>
 *  <p> ...<p>
 * <p>]<p>
 * <p># Indexes: 索引名(列,列) 索引名(列)<p>
 *
 * @author Li
 * @since 2026-10-21
 */
@Component
public class SchemaRenderer {

    /** 库标识。ERP 单库，写死即可。 */
    private static final String DB_ID = "erp";

    /**
     * 渲染 Schema 文本。
     *
     * @param tables    可见表
     * @param columns   可见列，按表名分组
     * @param relations 可见关系，两端表都必须在可见表里
     * @return Schema 文本
     */
    public String render(List<AiDataTable> tables, Map<String, List<AiDataTableColumn>> columns,
                         List<AiDataTableRelation> relations) {
        if (tables.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【DB_ID】 ").append(DB_ID).append('\n'); // 写库标识
        Set<String> visibleTables = new LinkedHashSet<>();
        for (AiDataTable table : tables) {
            visibleTables.add(table.getTableName());  // 收集可见表名
            renderTable(sb, table, columns.getOrDefault(table.getTableName(), List.of())); // 逐表渲染

        }
        renderRelations(sb, relations, visibleTables);  // 渲染关系
        return sb.toString();
    }

    /**
     * 渲染一张表。
     */
    private void renderTable(StringBuilder sb, AiDataTable table, List<AiDataTableColumn> columns) {
        sb.append("# Table: ").append(table.getTableName()).append(", ").append(table.getBusinessName()).append('\n');
        String description = table.getDescription();
        if (description != null && !description.isBlank() && !description.equals(table.getBusinessName())) {
            sb.append("# ").append(description).append('\n');
        }
        sb.append("[\n");
        List<String> lines = new ArrayList<>();
        for (AiDataTableColumn column : columns) {
            // 渲染列文本 (列1:类型, 描述, Primary Key/Unique, Examples: [...]),
            lines.add(renderColumn(column));
        }
        sb.append(String.join(",\n", lines)).append("\n]\n");
        String indexes = renderIndexes(table.getIndexDefs());  // 渲染索引
        if (!indexes.isEmpty()) {
            sb.append("# Indexes: ").append(indexes).append('\n');
        }
    }

    /**
     * 渲染一列。
     */
    private String renderColumn(AiDataTableColumn column) {
        // (列名:类型
        StringBuilder line = new StringBuilder("(")
                .append(column.getColumnName()).append(':')
                .append(column.getColumnType() == null ? "" : column.getColumnType().toUpperCase(Locale.ROOT));
        List<String> enums = column.getEnumValues() == null ? List.of() : column.getEnumValues();
        boolean numericEnum = isNumericEnum(enums);
        // 1. 描述：优先用 description，没有就用 businessName
        String comment = column.getDescription();
        if (comment == null || comment.isBlank()) {
            comment = column.getBusinessName();
        }
        if (comment != null && !comment.isBlank() && !comment.equals(column.getColumnName())) {
            // 数字枚举：描述原样保留
            // 字母枚举：把枚举值从描述里删掉（因为后面会单独给 Examples）
            String cleaned = numericEnum ? comment : stripEnumValues(comment, enums);
            if (!cleaned.isBlank()) {
                line.append(", ").append(cleaned);
            }
        }
        // 2. 主键标 Primary Key、唯一键标 Unique
        if (Integer.valueOf(1).equals(column.getPrimaryKey())) {
            line.append(", Primary Key");
        } else if (Integer.valueOf(1).equals(column.getUniqueKey())) {
            line.append(", Unique");
        }
        // 3. 字母枚举给 Examples，数字枚举不给
        if (!enums.isEmpty() && !numericEnum) {
            line.append(", Examples: ").append(renderExamples(enums));
        }
        return line.append(")").toString();
    }

    /**
     * 渲染索引：名称(列,列) 名称(列)。
     */
    private String renderIndexes(List<IndexDefinition> indexes) {
        if (indexes == null || indexes.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (IndexDefinition index : indexes) {
            parts.add(index.name() + "(" + String.join(",", index.columns()) + ")");
        }
        // 索引名(列,列)
        return String.join(" ", parts);
    }

    /**
     * 渲染关系块：只给两端表都可见的关系。
     *
     * <p>采购员看不到 {@code purchase_order} 时，
     * {@code purchase_order_item → purchase_order} 这条关系也不给——
     * 否则模型会去 JOIN 一个 Schema 里不存在的表。</p>
     */
    private void renderRelations(StringBuilder sb, List<AiDataTableRelation> relations,
                                 Set<String> visibleTables) {
        if (relations == null || relations.isEmpty()) {
            return;
        }
        List<String> lines = new ArrayList<>();
        for (AiDataTableRelation relation : relations) {
            // 两端表都必须可见，否则跳过
            if (!visibleTables.contains(relation.getFromTable())
                    || !visibleTables.contains(relation.getToTable())) {
                continue;
            }
            //  渲染：from_table.from_col = to_table.to_col [基数] 描述 (基数：1:1、N:1、1:N)
            StringBuilder line = new StringBuilder(relation.getFromTable()).append('.')
                    .append(relation.getFromColumn()).append('=')
                    .append(relation.getToTable()).append('.')
                    .append(relation.getToColumn());
            if (relation.getCardinality() != null && !relation.getCardinality().isBlank()) {
                line.append(" [").append(relation.getCardinality()).append(']');
            }
            if (relation.getDescription() != null && !relation.getDescription().isBlank()) {
                line.append(' ').append(relation.getDescription());
            }
            lines.add(line.toString());
        }
        if (lines.isEmpty()) {
            return;
        }
        sb.append("\n【Foreign keys】\n").append(String.join("\n", lines)).append('\n');
    }

    /**
     * 把描述里重复出现的枚举值删掉，避免与 Examples 重复。
     *
     * <p>标点清理要保守：只合并连续分隔符、去掉首尾分隔符，不动句子中间的标点，
     * 否则「历史数据中可能残留旧编码，等同于规范编码」会被清理成读不通的残句。</p>
     */
    private String stripEnumValues(String comment, List<String> enums) {
        if (comment == null || comment.isEmpty() || enums.isEmpty() || isNumericEnum(enums)) {
            return comment;
        }
        String result = comment;
        // 逐个删掉枚举值
        for (String value : enums) {
            if (value != null && !value.isEmpty()) {
                // 状态：DRAFT草稿，CONFIRMED已确认".replace("DRAFT", "")
                result = result.replace(value, "");
            }
        }
        // 清理连续分隔符
        result = result.replaceAll("[、，,；;]{2,}", "");
        // 清理开头的分隔符
        result = result.replaceAll("^[、，,；;：:]+", "");
        // 清理结尾的分隔符
        result = result.replaceAll("[、，,；;：:]+$", "");
        // 修复"冒号+逗号"
        result = result.replace("：，", "：");
        // 合并多余空格
        result = result.replaceAll(" {2,}", " ");
        return result.trim();
    }

    /**
     * 数字枚举的取值含义已由描述里的自然语言说明，不再单独给 Examples。
     */
    private boolean isNumericEnum(List<String> enums) {
        if (enums == null || enums.isEmpty()) {
            return false;
        }
        for (String value : enums) {
            if (value == null || !value.matches("\\d+")) {
                return false;
            }
        }
        return true;
    }

    /**
     * 渲染 Examples：[值,值]。
     */
    private String renderExamples(List<String> enums) {
        List<String> quoted = new ArrayList<>();
        for (String value : enums) {
            quoted.add('"' + value + '"');
        }
        return "[" + String.join(", ", quoted) + "]";
    }

    /**
     * 按表名给列分组。
     */
    public Map<String, List<AiDataTableColumn>> groupByTable(List<AiDataTableColumn> columns) {
        Map<String, List<AiDataTableColumn>> grouped = new LinkedHashMap<>();
        for (AiDataTableColumn column : columns) {
            grouped.computeIfAbsent(column.getTableName(), ignored -> new ArrayList<>()).add(column);
        }
        return grouped;
    }
}
