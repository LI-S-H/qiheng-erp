package com.qiheng.erp.ai.report;

import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import java.io.IOException;
import java.util.Map;

/** 报告环节只开放读取能力，身份仍由受信任的工具上下文提供。 */
public final class ReportReadTools {
    private final ReadDatasetTool datasets;
    private final AnalysisResultTool results;

    public ReportReadTools(ReadDatasetTool datasets, AnalysisResultTool results) {
        this.datasets = datasets;
        this.results = results;
    }

    /** 读取已发布数据集的一页。 */
    @Tool(name = "readDataset", description = "按数据集 ID 分页读取已发布的业务数据及完整性信息。")
    public Object readDataset(@ToolParam(description = "数据集 ID") String datasetId,
            @ToolParam(required = false, description = "从第几行开始，默认 0") Integer offset,
            @ToolParam(required = false, description = "最多读取行数") Integer limit, ToolContext context) {
        return datasets.readDataset(datasetId, offset, limit, context);
    }

    /** 读取服务器上已保存的文本分析结果。 */
    @Tool(name = "readAnalysisResult", description = "按结果 ID 读取已保存的文本、CSV、JSON 或 JSONL 分析结果。")
    public Map<String, Object> readAnalysisResult(@ToolParam(description = "分析结果 ID") String resultId,
            ToolContext context) throws IOException {
        return results.readAnalysisResult(resultId, context);
    }
}
