package com.qiheng.erp.ai.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.model.IndexDefinition;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * 索引定义列表的 JSON 类型处理器。
 *
 * <p>{@code ai_data_table.index_defs} 存的是 {@code [{"name":..,"unique":..,"columns":[..]}]}
 * 这样的数组，用 {@link JsonStringListTypeHandler} 那套写法处理字符串列表不够用，
 * 这里单独处理对象数组。序列化与反序列化都用独立的 ObjectMapper，
 * 不依赖应用级配置，避免 Long→String 那类全局定制影响这里。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
@MappedTypes({List.class})
@MappedJdbcTypes({JdbcType.VARCHAR})
public class JsonIndexDefinitionListTypeHandler extends BaseTypeHandler<List<IndexDefinition>> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final TypeReference<List<IndexDefinition>> TYPE = new TypeReference<>() {
    };

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, List<IndexDefinition> parameter,
                                    JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, MAPPER.writeValueAsString(parameter));
        } catch (JsonProcessingException e) {
            throw new SQLException("索引定义序列化失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<IndexDefinition> getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return read(rs.getString(columnName));
    }

    @Override
    public List<IndexDefinition> getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return read(rs.getString(columnIndex));
    }

    @Override
    public List<IndexDefinition> getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return read(cs.getString(columnIndex));
    }

    /**
     * 解析索引定义 JSON；空值按空数组处理，解析失败不吞异常——
     * 索引解析不了说明登记数据坏了，宁可报错也不要静默给模型一份错的索引。
     */
    private List<IndexDefinition> read(String json) throws SQLException {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<IndexDefinition> definitions = MAPPER.readValue(json, TYPE);
            return definitions == null ? List.of() : definitions;
        } catch (JsonProcessingException e) {
            throw new SQLException("索引定义反序列化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析索引定义 JSON 文本。空值与空串按空数组处理；解析失败抛 {@link SQLException}。
     *
     * <p>对同步程序幂等很关键：读回来的定义必须与库里读到的事实逐项相等，
     * 否则每次同步都会判定「索引变了」，反复刷新同一份数据。</p>
     *
     * @param json 索引定义 JSON 文本
     * @return 索引定义列表
     * @throws SQLException JSON 不合法
     */
    public List<IndexDefinition> parse(String json) throws SQLException {
        return read(json);
    }
}
