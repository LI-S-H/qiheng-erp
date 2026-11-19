package com.qiheng.erp.ai.report;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** 主智能体向报告工具交接的一步实际分析及其依据。 */
public record ReportAnalysisStep(
        @JsonPropertyDescription("分析步骤顺序，从 1 开始") int step,
        @JsonPropertyDescription("本步骤的分析目的，说明为什么要做") String purpose,
        @JsonPropertyDescription("本步骤实际执行的查询或分析操作") String action,
        @JsonPropertyDescription("本步骤的具体发现或结论，关键数字需写明单位和口径") String finding,
        @JsonPropertyDescription("支持本步骤结论的 SQL 数据集 ID 数组，没有时为空数组") List<String> datasetIds,
        @JsonPropertyDescription("支持本步骤结论的分析结果文件 ID 数组，没有时为空数组") List<String> resultIds
) {}
