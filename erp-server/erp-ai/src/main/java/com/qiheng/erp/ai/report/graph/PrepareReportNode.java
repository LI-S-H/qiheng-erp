package com.qiheng.erp.ai.report.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.report.ReportAnalysisStep;
import com.qiheng.erp.ai.report.ReportProgress;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** 整理本轮需求、追问及分析步骤，生成报告智能体的完整输入。 */
final class PrepareReportNode implements NodeAction {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PrepareReportNode.class);
    private final ReportConversationReader conversations;
    private final AgentToolContext identity;
    private final String runId;
    private final ReportMaterialPreparer materials;
    private final ReportProgress progress;

    PrepareReportNode(ReportConversationReader conversations, AgentToolContext identity, String runId,
                      ReportMaterialPreparer materials) {
        this(conversations, identity, runId, materials, null);
    }

    PrepareReportNode(ReportConversationReader conversations, AgentToolContext identity, String runId,
                      ReportMaterialPreparer materials, ReportProgress progress) {
        this.conversations = conversations;
        this.identity = identity;
        this.runId = runId;
        this.materials = materials;
        this.progress = progress;
    }

    /** 本次材料以一段文本交给报告智能体，依据仍保留在步骤中。 */
    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> apply(OverAllState state) {
        if (progress != null) {
            progress.stage("PREPARING", "正在整理报告需求和资料。");
        }
        Map<String, Object> work = ReportNodes.work(state);
        Map<String, Object> input = (Map<String, Object>) work.get("input");
        List<ReportAnalysisStep> steps = (List<ReportAnalysisStep>) input.get("analysisSteps");
        try {
            List<Map<String, String>> unavailable = materials.unavailableReferences(steps, identity);
            if (!unavailable.isEmpty()) {
                work.put("REFERENCE_UNAVAILABLE", Map.of(
                        "message", "报告所需资料不存在、已过期或当前无法访问。",
                        "unavailableReferences", unavailable,
                        "suggestion", "请核对资料；需要时重新取得数据并更新参数。"));
                return Map.of(ReportGraph.WORK, work);
            }
            return prepare(work, input, steps);
        } catch (Exception error) {
            log.warn("报告准备失败 errorType={}", error.getClass().getSimpleName());
            work.put("PREPARE_FAILED", Map.of("message", "报告任务准备失败，请稍后重试。"));
            return Map.of(ReportGraph.WORK, work);
        }
    }

    /** 保留问题与回答的原始业务文字，拼接为报告专家输入。 */
    private Map<String, Object> prepare(Map<String, Object> work, Map<String, Object> input,
                                        List<ReportAnalysisStep> steps) throws Exception {
        StringBuilder text = new StringBuilder(conversations.read(identity, runId));
        text.append("\n\n分析过程：\n");
        // 主智能体可能没有按编号传递，拼接时按步骤顺序呈现。
        for (ReportAnalysisStep step : steps.stream().sorted(Comparator.comparingInt(ReportAnalysisStep::step)).toList()) {
            text.append("\n第 ").append(step.step()).append(" 步\n");
            text.append("目的：").append(step.purpose()).append("\n");
            text.append("操作：").append(step.action()).append("\n");
            text.append("结论：").append(step.finding()).append("\n");
            text.append("依赖数据集：").append(formatIds(step.datasetIds())).append("\n");
            text.append("结果文件：").append(formatIds(step.resultIds())).append("\n");
        }
        String limitations = (String) input.get("limitations");
        if (limitations != null && !limitations.isBlank()) {
            text.append("\n整体局限：\n").append(limitations);
        }
        work.remove("input");
        work.put("reportInput", text.toString());
        work.put("revisionCount", 0);
        return Map.of(ReportGraph.WORK, work);
    }

    /** 没有依据时直接标明，避免把 null 显示给模型。 */
    private String formatIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return "无";
        }
        return String.join("、", ids);
    }
}
