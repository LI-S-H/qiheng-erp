package com.qiheng.erp.ai.report;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import java.util.List;

/** 完成报告审阅后给出的总体结果。 */
public record ReportReview(
        @JsonPropertyDescription("报告整体评分，范围为 0 到 10") Double score,
        @JsonPropertyDescription("报告的主要优点；没有时返回空数组") List<String> strengths,
        @JsonPropertyDescription("报告是否存在引用编号不对应或引用资料错误等问题") Boolean hasReferenceIssue,
        @JsonPropertyDescription("需要修改的问题及建议；没有问题时返回空数组") List<ReportReviewIssue> issues
) {}
