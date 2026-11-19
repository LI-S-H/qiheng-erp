package com.qiheng.erp.ai.report;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/** 审阅发现的一项问题及建议。 */
public record ReportReviewIssue(
        @JsonPropertyDescription("具体问题；必要时说明相关章节或原文") String problem,
        @JsonPropertyDescription("作出该判断的具体原因；涉及数据时说明报告内容与依据的差异") String reason,
        @JsonPropertyDescription("可执行的报告修改建议") String suggest
) {}
