package com.qiheng.erp.ai.sql.registry;

import com.qiheng.erp.ai.domain.model.ColumnFacts;
import com.qiheng.erp.ai.domain.model.IndexFacts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 库结构读取：从 information_schema 读出列与索引的事实。
 *
 * <p>只读结构，不带任何业务语义——业务语义在登记表里，由人工维护。</p>
 *
 * <p>单独成组件是为了让同步逻辑能脱离数据库单测：同步服务只面对「列事实」和「索引事实」
 * 两组数据，怎么比对、怎么写回是自己的事，不该被 SQL 细节绑住。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaLibraryReader {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 读指定表的列事实：类型、可空、主键。
     *
     * @param tables 表名集合
     * @return 表名到列事实列表的映射
     */
    public Map<String, List<ColumnFacts>> readColumns(List<String> tables) {
        Map<String, List<ColumnFacts>> result = new LinkedHashMap<>();
        if (tables.isEmpty()) {
            return result;
        }
        String sql = """
                SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_KEY
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (%s)
                ORDER BY TABLE_NAME, ORDINAL_POSITION
                """.formatted(placeholders(tables.size()));
        RowCallbackHandler handler = rs -> {
            String tableName = rs.getString("TABLE_NAME").toLowerCase();
            int primaryKey = "PRI".equals(rs.getString("COLUMN_KEY")) ? 1 : 0;
            result.computeIfAbsent(tableName, ignored -> new ArrayList<>()).add(new ColumnFacts(
                    tableName, rs.getString("COLUMN_NAME"), rs.getString("COLUMN_TYPE"),
                    "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")) ? 1 : 0, primaryKey, 0));
        };
        jdbcTemplate.query(sql, handler, tables.toArray());
        return result;
    }

    /**
     * 读指定表的索引事实。
     *
     * @param tables 表名集合
     * @return 表名到索引事实列表的映射；主键索引不在其中
     */
    public Map<String, List<IndexFacts>> readIndexes(List<String> tables) {
        Map<String, List<IndexFacts>> result = new LinkedHashMap<>();
        if (tables.isEmpty()) {
            return result;
        }
        String sql = """
                SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN (%s)
                ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
                """.formatted(placeholders(tables.size()));
        Map<String, Map<String, List<String>>> columns = new LinkedHashMap<>();
        Map<String, Map<String, Boolean>> uniqueFlags = new LinkedHashMap<>();
        RowCallbackHandler handler = rs -> {
            String tableName = rs.getString("TABLE_NAME").toLowerCase();
            String indexName = rs.getString("INDEX_NAME");
            // NON_UNIQUE：0 表示唯一索引，1 表示普通索引
            boolean unique = "0".equals(rs.getString("NON_UNIQUE"));
            columns.computeIfAbsent(tableName, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(indexName, ignored -> new ArrayList<>()).add(rs.getString("COLUMN_NAME"));
            uniqueFlags.computeIfAbsent(tableName, ignored -> new LinkedHashMap<>()).put(indexName, unique);
        };
        jdbcTemplate.query(sql, handler, tables.toArray());
        for (Map.Entry<String, Map<String, List<String>>> entry : columns.entrySet()) {
            Map<String, Boolean> flags = uniqueFlags.get(entry.getKey());
            List<IndexFacts> facts = new ArrayList<>();
            for (Map.Entry<String, List<String>> index : entry.getValue().entrySet()) {
                if ("PRIMARY".equals(index.getKey())) {
                    continue;
                }
                facts.add(new IndexFacts(index.getKey(), Boolean.TRUE.equals(flags.get(index.getKey())),
                        index.getValue()));
            }
            result.put(entry.getKey(), facts);
        }
        return result;
    }

    private String placeholders(int size) {
        return String.join(",", Collections.nCopies(Math.max(size, 1), "?"));
    }
}
