package com.qiheng.erp.ai.report.graph;

import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.report.ReportAgents;
import com.qiheng.erp.ai.report.ReportAnalysisStep;
import com.qiheng.erp.ai.report.ReportProgress;
import com.qiheng.erp.ai.report.ReportStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/** 报告图独立入口；可信身份仅供工具使用，不交给模型填写。 */
public final class ReportService {
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);
    private final ReportAgents agents;
    private final AiConversationMapper conversations;
    private final ReportMaterialPreparer materials;
    private final ReportConversationReader messageReader;
    private final ReportStore store;

    public ReportService(ReportAgents agents, AiConversationMapper conversations, ReportMaterialPreparer materials) {
        this(agents, conversations, materials, null);
    }

    /** 同时装配主智能体调用所需的本轮会话读取器。 */
    public ReportService(ReportAgents agents, AiConversationMapper conversations, ReportMaterialPreparer materials,
                         ReportConversationReader messageReader) {
        this(agents, conversations, materials, messageReader, null);
    }

    public ReportService(ReportAgents agents, AiConversationMapper conversations, ReportMaterialPreparer materials,
                         ReportConversationReader messageReader, ReportStore store) {
        this.agents = agents;
        this.conversations = conversations;
        this.materials = materials;
        this.messageReader = messageReader;
        this.store = store;
    }

    /** 校验会话归属并为本次执行绑定可信身份。 */
    public Map<String, Object> run(Map<String, Object> input, AgentToolContext identity) throws Exception {
        if (identity == null || conversations.selectOwned(identity.conversationId(), identity.userId()) == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        var graph = new ReportGraph(state -> agents.write(state, identity),
                state -> agents.review(state, identity), values -> materials.prepare(values, identity));
        return graph.run(input);
    }

    /** 接收主智能体的分析步骤，按当前轮补充用户需求并生成报告。 */
    public Map<String, Object> runForMainAgent(List<ReportAnalysisStep> analysisSteps, String limitations,
                                                AgentToolContext identity, String runId) throws Exception {
        return runForMainAgent(analysisSteps, limitations, identity, runId, null);
    }

    /** 真实工具调用以本轮可信标识交付报告并推送过程。 */
    public Map<String, Object> runForMainAgent(List<ReportAnalysisStep> analysisSteps, String limitations,
                                               AgentToolContext identity, String runId, ReportProgress progress) throws Exception {
        if (identity == null || conversations.selectOwned(identity.conversationId(), identity.userId()) == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        if (runId == null || runId.isBlank() || messageReader == null) {
            throw new IllegalStateException("缺少本轮报告上下文");
        }
        if (store != null && progress == null) {
            throw new IllegalStateException("缺少可信报告工具调用标识");
        }
        if (analysisSteps == null || analysisSteps.isEmpty()) {
            throw new IllegalArgumentException("报告分析步骤不能为空");
        }
        for (ReportAnalysisStep step : analysisSteps) {
            if (step == null || step.step() < 1 || step.purpose() == null || step.purpose().isBlank()
                    || step.action() == null || step.action().isBlank()
                    || step.finding() == null || step.finding().isBlank()) {
                throw new IllegalArgumentException("报告分析步骤需包含顺序、目的、操作和具体结论");
            }
        }
        Map<String, Object> result;
        try (var task = progress == null ? agents.openTask(identity) : agents.openTask(identity, progress)) {
            var graph = new ReportGraph(
                    new PrepareReportNode(messageReader, identity, runId, materials, progress),
                    new ReportWorkflowNodes.PrepareDecide(),
                    new ReportWorkflowNodes.Write(task::write, progress),
                    new ReportWorkflowNodes.WriteDecide(),
                    new ReportWorkflowNodes.Review(task::review, progress),
                    new ReportWorkflowNodes.ReviewDecide(progress));
            var input = Map.<String, Object>of("analysisSteps", analysisSteps,
                    "limitations", limitations == null ? "" : limitations);
            result = graph.run(input);
        } catch (Exception error) {
            if (progress == null) {
                throw error;
            }
            log.warn("报告执行失败，runId={}，异常类型={}", runId, error.getClass().getSimpleName());
            progress.stage("FAILED", "报告生成未完成，请稍后重试。");
            return Map.of("status", "FAILED", "message", "报告生成未完成，请稍后重试。");
        }
        if ("COMPLETED".equals(result.get("status"))) {
            if (store == null) {
                return Map.of("status", "COMPLETED", "report", result.get("content"));
            }
            return publish(identity, runId, result, progress);
        }
        for (String key : List.of("REFERENCE_UNAVAILABLE", "PREPARE_FAILED", "WRITE_FAILED",
                "REPORT_FORMAT_FAILED", "REVIEW_FORMAT_FAILED", "REVIEW_FAILED", "REVIEW_INCONSISTENT")) {
            if (result.get(key) instanceof Map<?, ?> failure) {
                if (progress != null) {
                    progress.stage("FAILED", String.valueOf(failure.get("message")));
                }
                return Map.of("status", "FAILED", key, failure);
            }
        }
        if (progress != null) {
            progress.stage("FAILED", "报告生成未完成，请稍后重试。");
        }
        return Map.of("status", "FAILED", "message", "报告生成未完成，请稍后重试。");
    }

    /** 正式报告写入成功后才能对模型与前端声明可用。 */
    private Map<String, Object> publish(AgentToolContext identity, String runId, Map<String, Object> result,
                                        ReportProgress progress) {
        progress.stage("SAVING", "报告已通过审阅，正在保存。");
        try {
            var saved = store.save(identity, runId, progress.toolCallId(), (String) result.get("title"),
                    (String) result.get("content"));
            progress.ready(Map.of("reportId", saved.reportId(), "requestId", saved.requestId(),
                    "title", saved.title(), "placeholder", saved.placeholder()));
            return Map.of("status", "COMPLETED", "placeholder", saved.placeholder(),
                    "displayInstruction", "完整报告已保存。请用一小段话交代主要结论和关键依据，将详细分析留在报告中。最终回复中原样独立输出一行 "
                            + saved.placeholder() + "。该位置会展示完整报告的入口。");
        } catch (RuntimeException error) {
            log.warn("报告保存失败，runId={}，异常类型={}", runId, error.getClass().getSimpleName());
            progress.stage("FAILED", "审核通过，但报告保存失败，请稍后重试。");
            return Map.of("status", "FAILED", "message", "报告保存失败，请稍后重试。");
        }
    }
}
