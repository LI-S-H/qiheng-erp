package com.qiheng.erp.ai.service.data.sql.execution.model;

/**
 * 结果列元数据。
 *
 * @param name      物理列名
 * @param label     结果标签（带别名时是别名）
 * @param jdbcType  JDBC 类型码
 * @param typeName  数据库类型名
 * @param nullable  是否可空
 * @author Li
 * @since 2026-10-26
 */
public record SqlQueryColumn(String name, String label, int jdbcType, String typeName,
                             boolean nullable) {
}