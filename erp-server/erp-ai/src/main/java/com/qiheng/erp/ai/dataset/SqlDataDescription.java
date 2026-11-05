package com.qiheng.erp.ai.dataset;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** 检查通过后，向主智能体说明数据集内容与使用边界。 */
public record SqlDataDescription(
        @JsonPropertyDescription("数据集内容及业务范围的简洁说明") String content,
        @JsonPropertyDescription("每行代表的业务对象或维度") String grain,
        @JsonPropertyDescription("按 columns 顺序描述全部结果列，不能添加不存在的列") List<SqlDataField> fields,
        @JsonPropertyDescription("数据使用限制；没有限制时填写空字符串") String limitations) {
}
