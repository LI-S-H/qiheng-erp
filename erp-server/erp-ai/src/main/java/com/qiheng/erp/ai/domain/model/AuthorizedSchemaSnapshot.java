package com.qiheng.erp.ai.domain.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 授权 Schema 快照：一份数据，两种形态。
 *
 * <p><b>文本</b>给模型看（{@link #schemaText()}），<b>结构化清单</b>给代码看。
 * SQL 守卫要判断"这条 SQL 查的表在不在可见范围内""这个列是不是被屏蔽的敏感列"
 * "这张表有没有软删除列""排序列可不可空""主键是哪一列"，靠正则从渲染文本里抠
 * 是不可靠的——渲染格式一改，守卫就跟着失效。</p>
 *
 * <p>快照进 State 时是 JSON 兼容结构（{@link #toMap()}），出检查点再还原（{@link #fromMap(Map)}）。</p>
 *
 * @param schemaText  给模型看的 Schema 文本
 * @param tables      可见表清单
 * @param columns     表 → 该表下发给模型的列
 * @param fingerprint 可见范围的指纹：表与列的集合哈希，用来判断范围有没有变
 * @author Li
 * @since 2026-10-25
 */
public record AuthorizedSchemaSnapshot(
        String schemaText,
        List<Table> tables,
        Map<String, List<Column>> columns,
        String fingerprint) {

    /**
     * 一张可见表。
     *
     * @param name          物理表名
     * @param businessName  业务名称
     * @param moduleCode    所属模块
     * @param logicalDelete 该表是否有软删除列（有则查询必须带过滤条件）
     * @param primaryKey    主键列名；登记里没有主键时为 null
     */
    public record Table(String name, String businessName, String moduleCode,
                        boolean logicalDelete, String primaryKey) {
    }

    /**
     * 一个下发给模型的列。
     *
     * @param name       物理列名
     * @param nullable   是否可空（可空列不能当翻页排序键）
     * @param primaryKey 是否主键
     */
    public record Column(String name, boolean nullable, boolean primaryKey) {
    }

    public AuthorizedSchemaSnapshot {
        tables = List.copyOf(tables);
        Map<String, List<Column>> copied = new LinkedHashMap<>();
        columns.forEach((table, names) -> copied.put(table, List.copyOf(names)));
        columns = Collections.unmodifiableMap(copied);
    }

    /**
     * 空快照：该用户没有任何可见表。
     *
     * @return 空快照
     */
    public static AuthorizedSchemaSnapshot empty() {
        return new AuthorizedSchemaSnapshot("", List.of(), Map.of(), fingerprintOf(List.of(), Map.of()));
    }

    /**
     * 造一个快照并算好指纹。
     *
     * @param schemaText Schema 文本
     * @param tables     可见表清单
     * @param columns    表 → 可见列
     * @return 快照
     */
    public static AuthorizedSchemaSnapshot of(String schemaText, List<Table> tables,
                                              Map<String, List<Column>> columns) {
        return new AuthorizedSchemaSnapshot(schemaText, tables, columns,
                fingerprintOf(tables, columns));
    }

    /**
     * 可见表名集合。
     *
     * @return 表名集合
     */
    public Set<String> tableNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Table table : tables) {
            names.add(table.name());
        }
        return names;
    }

    /**
     * 全部可见列名（跨表并集）。
     *
     * @return 列名集合
     */
    public Set<String> allColumnNames() {
        Set<String> names = new LinkedHashSet<>();
        columns.values().forEach(list -> list.forEach(column -> names.add(column.name())));
        return names;
    }

    /**
     * 某张表是否可见。
     *
     * @param tableName 表名
     * @return 可见时返回 true
     */
    public boolean hasTable(String tableName) {
        return tableNames().contains(tableName);
    }

    /**
     * 某张表的某个列是否下发给过模型。
     *
     * @param tableName  表名
     * @param columnName 列名
     * @return 下发过返回 true
     */
    public boolean hasColumn(String tableName, String columnName) {
        return findColumn(tableName, columnName) != null;
    }

    /**
     * 查一个列的定义。
     *
     * @param tableName  表名
     * @param columnName 列名
     * @return 列定义；不存在时返回 null
     */
    public Column findColumn(String tableName, String columnName) {
        List<Column> list = columns.get(tableName);
        if (list == null) {
            return null;
        }
        for (Column column : list) {
            if (column.name().equalsIgnoreCase(columnName)) {
                return column;
            }
        }
        return null;
    }

    /**
     * 某张表下发给模型的列名。
     *
     * @param tableName 表名
     * @return 列名列表；表不存在时返回空列表
     */
    public List<String> columnNames(String tableName) {
        List<Column> list = columns.get(tableName);
        if (list == null) {
            return List.of();
        }
        return list.stream().map(Column::name).toList();
    }

    /**
     * 某张表的主键列名。
     *
     * @param tableName 表名
     * @return 主键列名；没有主键或表不可见时返回 null
     */
    public String primaryKeyOf(String tableName) {
        for (Table table : tables) {
            if (table.name().equalsIgnoreCase(tableName)) {
                return table.primaryKey();
            }
        }
        return null;
    }

    /**
     * 某张表是否有软删除列。
     *
     * @param tableName 表名
     * @return 有软删除列返回 true
     */
    public boolean hasLogicalDelete(String tableName) {
        return tables.stream()
                .anyMatch(table -> table.name().equalsIgnoreCase(tableName) && table.logicalDelete());
    }

    /**
     * 转成 State 里存的 JSON 兼容结构。
     *
     * @return 结构化快照
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("schema", schemaText == null ? "" : schemaText);
        map.put("tableCount", tables.size());
        List<Map<String, Object>> tableMaps = new ArrayList<>(tables.size());
        for (Table table : tables) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", table.name());
            item.put("businessName", table.businessName());
            item.put("module", table.moduleCode());
            item.put("logicalDelete", table.logicalDelete());
            item.put("primaryKey", table.primaryKey());
            tableMaps.add(item);
        }
        map.put("tables", tableMaps);
        Map<String, Object> columnMap = new LinkedHashMap<>();
        columns.forEach((table, list) -> {
            List<Map<String, Object>> items = new ArrayList<>(list.size());
            for (Column column : list) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", column.name());
                item.put("nullable", column.nullable());
                item.put("primaryKey", column.primaryKey());
                items.add(item);
            }
            columnMap.put(table, items);
        });
        map.put("columns", columnMap);
        map.put("fingerprint", fingerprint);
        return map;
    }

    /**
     * 从 State 里的结构还原：缺字段不报错，按空值处理。
     *
     * @param map State 里的结构
     * @return 快照
     */
    public static AuthorizedSchemaSnapshot fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return empty();
        }
        List<Table> tables = new ArrayList<>();
        Object rawTables = map.get("tables");
        if (rawTables instanceof Collection<?> collection) {
            for (Object item : collection) {
                if (!(item instanceof Map<?, ?> tableMap)) {
                    continue;
                }
                Object name = tableMap.get("name");
                if (name == null) {
                    continue;
                }
                tables.add(new Table(name.toString(), text(tableMap.get("businessName")),
                        text(tableMap.get("module")), Boolean.TRUE.equals(tableMap.get("logicalDelete")),
                        blankToNull(text(tableMap.get("primaryKey")))));
            }
        }
        Map<String, List<Column>> columns = new LinkedHashMap<>();
        Object rawColumns = map.get("columns");
        if (rawColumns instanceof Map<?, ?> columnMap) {
            for (Map.Entry<?, ?> entry : columnMap.entrySet()) {
                if (entry.getKey() == null || !(entry.getValue() instanceof Collection<?> items)) {
                    continue;
                }
                List<Column> list = new ArrayList<>();
                for (Object item : items) {
                    // 兼容旧结构：列直接是字符串时按"可见、非主键"处理
                    if (item instanceof Map<?, ?> column) {
                        Object name = column.get("name");
                        if (name != null) {
                            list.add(new Column(name.toString(),
                                    Boolean.TRUE.equals(column.get("nullable")),
                                    Boolean.TRUE.equals(column.get("primaryKey"))));
                        }
                    } else if (item != null) {
                        list.add(new Column(item.toString(), false, false));
                    }
                }
                columns.put(entry.getKey().toString(), list);
            }
        }
        return new AuthorizedSchemaSnapshot(text(map.get("schema")), tables, columns,
                text(map.get("fingerprint")));
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * 可见范围的指纹：表名与其列名的集合哈希。
     *
     * <p>只认集合，不认顺序——同样的可见范围换个渲染顺序，指纹不变。</p>
     */
    private static String fingerprintOf(List<Table> tables, Map<String, List<Column>> columns) {
        StringBuilder source = new StringBuilder();
        tables.stream().map(Table::name).sorted().forEach(name -> {
            source.append(name).append(':');
            List<String> names = new ArrayList<>();
            columns.getOrDefault(name, List.of()).forEach(column -> names.add(column.name()));
            names.sort(String::compareTo);
            source.append(String.join(",", names)).append(';');
        });
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            // JDK 一定有 SHA-256；真没有就只能退回不可用状态，不该在这里崩掉整轮任务
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}