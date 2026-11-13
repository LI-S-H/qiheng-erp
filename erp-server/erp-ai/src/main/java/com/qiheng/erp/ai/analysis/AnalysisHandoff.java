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
        @JsonPropertyDescription("本次分析任务结束后交给主智能体的回答：根据任务目标给出已取得的结论和必要数值，说明使用的数据依据、已核对的内容及影响可信度的实际限制；未能全部完成时，说明已完成的部分、具体缺口及其影响。不复述无关过程，不编造未确认的条件或结论")
        String summary) {
}
