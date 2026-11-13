package com.qiheng.erp.ai.dataset.tool;

import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.AnalysisDatasetSummaryService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;

import java.util.Map;

/**
 * 供智能体了解当前会话已有的 SQL 数据集及有限预览。
 *
 * @author Li
 * @since 2026-11-10
 */
public final class ListDatasetsTool {
    private final AnalysisDatasetSummaryService summaries;

    /** 复用数据集管理器的归属和访问权限校验。 */
    public ListDatasetsTool(DatasetManager datasets) {
        this.summaries = new AnalysisDatasetSummaryService(datasets);
    }

    /** 返回数据集信息及最多两行预览，不以预览代表完整数据。 */
    @Tool(name = "listDatasets", description = "列出当前会话可访问的已发布 SQL 数据集的字段、说明、行数和最多两行预览；completed 只表示本次查询读取是否完成，不代表业务范围一定全量，预览不代表全部数据。")
    public Map<String, Object> listDatasets(ToolContext toolContext) {
        var identity = ToolContextHelper.getMetadata(toolContext, AgentToolContext.CONTEXT_KEY, AgentToolContext.class)
                .orElseThrow(() -> new IllegalStateException("缺少可信数据集工具上下文"));
        var summary = summaries.listForAnalysis(identity);
        return Map.of("datasets", summary.datasets(), "unavailableCount", summary.unavailableCount(),
                "message", summary.message());
    }
}
