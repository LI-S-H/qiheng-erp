package com.qiheng.erp.ai.sql.graph.model.output;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** 模型已能生成查询时返回的字段。 */
public record SqlQueryOutput(
        @JsonPropertyDescription("固定为 QUERY，表示已生成查询 SQL") String decision,
        @JsonPropertyDescription("根据授权 Schema 和业务需求生成的一条只读 MySQL SELECT") String sql,
        @JsonPropertyDescription("是否需要完整结果；程序根据 SQL 结构选择执行方式，不能与 LIMIT 同时使用") Boolean needFullData
) implements SqlGenerationOutput {
}
