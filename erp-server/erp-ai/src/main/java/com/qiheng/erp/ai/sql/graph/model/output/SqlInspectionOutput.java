package com.qiheng.erp.ai.sql.graph.model.output;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** 结果检查节点的模型输出。 */
public record SqlInspectionOutput(
        @JsonPropertyDescription("检查决定：PASS 表示 SQL 和结果满足本次需求；RETRY 表示发现可交给 SQL 生成节点重新判断或修正的问题；FAILED 表示当前已知条件或能力下无法完成本次取数。") String decision,
        @JsonPropertyDescription("检查结论：PASS 时概述实际取得的数据及限制；RETRY 时指出 SQL 或结果哪里不符合原需求、影响是什么，以及需要生成节点重新核对的条件或修改方向；FAILED 时说明无法完成的具体原因。不得编造缺失的仓库、时间或业务口径。") String message,
        @JsonPropertyDescription("数据集说明，仅 PASS 时必须填写：描述数据内容、每行粒度、实际返回字段及已知限制，供主智能体理解数据；RETRY 和 FAILED 时省略或填写 null。") SqlInspectionDescription dataDescription
) {
}
