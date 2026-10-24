package com.qiheng.erp.ai.domain.model;

/**
 * 库里的列事实：来自 information_schema 的结构事实，不含任何业务语义。
 *
 * @param tableName  表名
 * @param columnName 列名
 * @param columnType 列类型
 * @param nullable   是否可空，1 可空 0 不可空
 * @param primaryKey 是否主键，1 是 0 否
 * @param uniqueKey  是否单列唯一键，1 是 0 否
 * @author Li
 * @since 2026-10-21
 */
public record ColumnFacts(String tableName, String columnName, String columnType,
                         int nullable, int primaryKey, int uniqueKey) {
}
