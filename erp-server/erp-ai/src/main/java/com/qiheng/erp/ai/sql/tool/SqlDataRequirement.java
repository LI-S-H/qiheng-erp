package com.qiheng.erp.ai.sql.tool;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.lang.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 主智能体提交的一次业务取数需求。 */
public record SqlDataRequirement(
        @JsonProperty(required = true) @JsonPropertyDescription("必填，非空字符串：完整描述查询目标及已知时间、仓库等筛选范围，不要猜测用户未提供的条件。") String goal,
        @JsonProperty(required = true) @JsonPropertyDescription("必填：FULL 表示要求业务范围内的完整结果；LIMITED 表示用户只需要指定数量或 Top N，数量和排序应写入 goal。") String dataRequirement,
        @Nullable @JsonPropertyDescription("可选字符串：每行代表的业务粒度，例如每个商品一行；不明确时省略。") String grain,
        @Nullable @JsonPropertyDescription("可选字符串数组：需要返回的业务字段，例如商品编号、库存数量；不明确时省略。") List<String> expectedFields,
        @Nullable @JsonPropertyDescription("可选字符串：本次数据的用途，用户未说明时省略。") String purpose) {

    /** 将已提供的业务字段写入子图状态，省略未填写的可选字段。 */
    public Map<String, Object> toMap() {
        Map<String, Object> requirement = new LinkedHashMap<>();
        requirement.put("goal", goal);
        requirement.put("dataRequirement", dataRequirement);
        if (grain != null) requirement.put("grain", grain);
        if (expectedFields != null) requirement.put("expectedFields", expectedFields);
        if (purpose != null) requirement.put("purpose", purpose);
        return requirement;
    }
}
