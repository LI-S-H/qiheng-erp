package com.qiheng.erp.ai.dataset;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** 数据集一列的业务含义，index 按查询结果列顺序从零开始。 */
public record SqlDataField(
        @JsonPropertyDescription("列的零起始序号，与 columns 中的顺序一致") int index,
        @JsonPropertyDescription("实际结果列的 label，必须与 columns 中对应列一致") String name,
        @JsonPropertyDescription("该列的业务含义，不猜测未提供的口径或单位") String meaning) {
}
