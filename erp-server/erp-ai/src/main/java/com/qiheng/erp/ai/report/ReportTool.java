package com.qiheng.erp.ai.report;

import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.qiheng.erp.ai.agent.TrustedToolContextInterceptor;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.report.graph.ReportService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.util.List;
import java.util.Map;

/** 接收主智能体的分析交接并生成报告。 */
public final class ReportTool {
    private final ReportService reports;

    public ReportTool(ReportService reports) {
        this.reports = reports;
    }

    /** 使用服务端上下文交接任务，不允许模型指定用户或轮次。 */
    @Tool(name = "createReport", description = "当业务分析步骤较多、过程复杂或信息较多时，整理成专业、清晰、美观的报告。可使用 Markdown 或 HTML 组织内容；简单问题可直接回答，无须调用。")
    public Map<String, Object> createReport(
            @ToolParam(description = "按顺序交接各步骤的目的、操作、具体发现及对应数据集和分析结果 ID") List<ReportAnalysisStep> analysisSteps,
            @ToolParam(required = false, description = "影响整体分析结论的数据范围、缺口或其他局限；没有时省略") String limitations,
            ToolContext context) throws Exception {
        AgentToolContext identity = ToolContextHelper.getMetadata(context, AgentToolContext.CONTEXT_KEY, AgentToolContext.class)
                .orElseThrow(() -> new IllegalStateException("缺少可信工具身份"));
        String runId = ToolContextHelper.getMetadata(context, TrustedToolContextInterceptor.CURRENT_RUN_ID, String.class)
                .orElseThrow(() -> new IllegalStateException("缺少当前运行标识"));
        var progress = ToolContextHelper.getMetadata(context, ReportProgress.METADATA_KEY, ReportProgress.class);
        if (progress.isPresent()) {
            return reports.runForMainAgent(analysisSteps, limitations, identity, runId, progress.get());
        }
        return reports.runForMainAgent(analysisSteps, limitations, identity, runId);
    }
}
