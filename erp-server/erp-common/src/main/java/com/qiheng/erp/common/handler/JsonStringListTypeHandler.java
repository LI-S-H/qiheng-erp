package com.qiheng.erp.common.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@MappedTypes({List.class})
@MappedJdbcTypes({JdbcType.VARCHAR})
public class JsonStringListTypeHandler extends BaseTypeHandler<List<String>> {
    /**
     * JSON字符串列表类型处理类
     * 用于将JSON字符串转换为Java列表对象，以及将Java列表对象转换为JSON字符串
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * JSON字符串列表类型引用
     */
    private static final TypeReference<List<String>> TYPE = new TypeReference<>() {};

    /**
     * 设置非空参数
     * @param ps
     * @param i
     * @param parameter
     * @param jdbcType
     * @throws SQLException
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<String> parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("JSON 序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取可空结果
     * @param rs
     * @param columnName
     * @return
     * @throws SQLException
     */
    @Override
    public List<String> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        try {
            String json = rs.getString(columnName);
            if (json == null) {
                return null;
            }
            return MAPPER.readValue(json, TYPE);
        } catch (JsonProcessingException e) {
            throw new SQLException("JSON 反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取可空结果
     * @param rs
     * @param columnIndex
     * @return
     * @throws SQLException
     */
    @Override
    public List<String> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        try {
            String json = rs.getString(columnIndex);
            if (json == null) {
                return null;
            }
            return MAPPER.readValue(json, TYPE);
        } catch (JsonProcessingException e) {
            throw new SQLException("JSON 反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取可空结果
     * @param cs
     * @param columnIndex
     * @return
     * @throws SQLException
     */
    @Override
    public List<String> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        try {
            String json = cs.getString(columnIndex);
            if (json == null) {
                return null;
            }
            return MAPPER.readValue(json, TYPE);
        } catch (JsonProcessingException e) {
            throw new SQLException("JSON 反序列化失败: " + e.getMessage(), e);
        }
    }
}
