package com.qiheng.erp.ai.analysis;

import com.qiheng.erp.ai.context.AgentToolContext;

/**
 * 一次分析任务的请求参数。
 *
 * <p>progress 为空表示独立分析或无事件通道，此时不发布分析过程事件。</p>
 *
 * @param goal 交办的分析目标
 * @param context 已确认的业务条件，可为空
 * @param identity 服务端确认的可信身份
 * @param progress 过程通道，可为空
 * @author Li
 * @since 2026-11-10
 */
public record AnalysisCommand(String goal, String context, AgentToolContext identity, AnalysisProgress progress) {
}
