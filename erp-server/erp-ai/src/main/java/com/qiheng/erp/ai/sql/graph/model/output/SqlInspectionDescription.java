package com.qiheng.erp.ai.sql.graph.model.output;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** 模型只解释结果列，列名由程序根据实际查询结果填入。 */
public record SqlInspectionDescription(
        @JsonPropertyDescription("数据集内容及业务范围的简洁说明") String content,
        @JsonPropertyDescription("每行代表的业务对象或维度") String grain,
        @JsonPropertyDescription("按输入 columns 的顺序解释全部结果列") List<Field> fields,
        @JsonPropertyDescription("数据使用限制；没有限制时填写空字符串") String limitations) {
    public record Field(
            @JsonPropertyDescription("列的零起始序号，与输入 columns 的顺序一致") int index,
            @JsonPropertyDescription("该列的业务含义，不猜测未提供的口径或单位") String meaning) {}
}
