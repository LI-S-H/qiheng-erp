package com.qiheng.erp.ai.sql.graph.model.output;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** 关键信息不足时返回具体缺口，不生成 SQL。 */
public record SqlNeedInfoOutput(
        @JsonPropertyDescription("固定为 NEED_INFO，表示缺少生成 SQL 的必要信息") String decision,
        @JsonPropertyDescription("缺少的信息，以及它为什么影响本次取数") String message
) implements SqlGenerationOutput {
}
