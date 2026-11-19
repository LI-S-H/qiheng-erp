package com.qiheng.erp.ai.analysis;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 分析智能体的业务结论；数据和文件引用由服务端组装。
 *
 * @author Li
 * @since 2026-11-10
 */
public record AnalysisHandoff(
        @JsonPropertyDescription("本次分析任务结束后的完成情况：COMPLETED 表示已完成任务目标；PARTIAL 表示只完成部分目标，其他部分受数据、权限或能力限制；NEED_INFO 表示完成目标所需的关键条件尚不明确，需要进一步确认")
        String completion,
        @JsonPropertyDescription("交给主智能体的 Markdown 分析交接：先给结论和关键数值，再按实际步骤说明用了哪些数据、做了什么、得到什么，并标明对应数据集或结果文件 ID。最后说明实际核对、缺口及影响。省略源代码和工具日志")
        String summary) {
}
