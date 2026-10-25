package com.qiheng.erp.ai.service.data.sql.execution;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * JDBC 值 → Java 值：保留类型，不字符串化。
 *
 * <p>全部转 String 是最省事也最坑的做法（DataAgent 就是这么干的）：日期不能排序、
 * BigDecimal 算出浮点误差、null 和空串分不清。这里按 JDBC 类型给回原生对象，
 * 序列化交给上层。</p>
 *
 * @author Li
 * @since 2026-10-26
 */
final class JdbcValueMapper {

    /** 二进制值上限：超过就只给一个长度说明，不把大块字节塞进 State。 */
    private static final int MAX_BINARY_BYTES = 1024;

    private JdbcValueMapper() {
    }

    /**
     * 读一列的值。
     *
     * @param resultSet 结果集
     * @param index     列下标（从 1 开始）
     * @param jdbcType  JDBC 类型码
     * @return Java 值；数据库为 NULL 时返回 null
     * @throws SQLException 读取失败
     */
    static Object read(ResultSet resultSet, int index, int jdbcType) throws SQLException {
        return switch (jdbcType) {
            case Types.DECIMAL, Types.NUMERIC -> resultSet.getBigDecimal(index);
            case Types.DATE -> resultSet.getObject(index, LocalDate.class);
            case Types.TIME -> resultSet.getObject(index, LocalTime.class);
            case Types.TIMESTAMP -> resultSet.getObject(index, LocalDateTime.class);
            case Types.TIMESTAMP_WITH_TIMEZONE -> resultSet.getObject(index, OffsetDateTime.class);
            case Types.BOOLEAN, Types.BIT -> resultSet.getObject(index, Boolean.class);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB ->
                    binary(resultSet.getBytes(index));
            default -> resultSet.getObject(index);
        };
    }

    /**
     * 二进制值有界：超过上限只返回长度标记。
     */
    private static Object binary(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes.length > MAX_BINARY_BYTES) {
            return "<binary:" + bytes.length + "bytes>";
        }
        return bytes;
    }
}