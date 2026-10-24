package com.qiheng.erp.ai.domain.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 索引定义，对应 {@code ai_data_table.index_defs} 数组中的一项。
 *
 * <p>索引是模型写 SQL 时的性能依据：过滤列、JOIN 列、排序列有没有索引，
 * 直接决定模型写出来的查询是走索引还是全表扫。慢 SQL 会拖垮数据库，
 * 所以索引要随表结构一起下发给模型。</p>
 *
 * <p>{@code @JsonProperty} 不能省：工程没有开启编译期 {@code -parameters}，
 * record 的构造器参数名不进 class 文件，Jackson 拿不到参数名就会把字段读成 null，
 * 反序列化出来的索引全是空值。</p>
 *
 * @param name    索引名
 * @param unique  是否唯一索引
 * @param columns 索引列，按索引内顺序
 * @author Li
 * @since 2026-10-21
 */
public record IndexDefinition(@JsonProperty("name") String name,
                              @JsonProperty("unique") boolean unique,
                              @JsonProperty("columns") List<String> columns) {

    public IndexDefinition {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
