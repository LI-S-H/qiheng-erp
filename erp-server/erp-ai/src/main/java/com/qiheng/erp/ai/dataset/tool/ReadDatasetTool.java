package com.qiheng.erp.ai.dataset.tool;

import com.qiheng.erp.ai.context.AgentToolContext;

import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetUnavailableException;
import java.util.Map;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** 按页读取已发布的数据集，不允许模型提供用户身份。 */
public final class ReadDatasetTool {
    private final DatasetManager datasets;

    public ReadDatasetTool(DatasetManager datasets) {
        this.datasets = datasets;
    }

    /** 根据可信会话读取授权数据集的一页。 */
    @Tool(name = "readDataset", description = "读取已查询并发布的数据集，默认从第 0 行开始。"
            + "单次最多返回约 8000 字符；超出时 truncated=true，用 nextOffset 继续读取。"
            + "hasMore 表示还有未读行，datasetTruncated 表示原查询本身不完整；不完整时不得当作完整数据。")
    public Object readDataset(
            @ToolParam(description = "已发布的数据集 ID") String datasetId,
            @ToolParam(required = false, description = "从第几行开始，默认 0") Integer offset,
            @ToolParam(required = false, description = "本次最多读取行数；省略时按 8000 字符预算尽量读取，可能少于全部") Integer limit,
            ToolContext toolContext) {
        AgentToolContext context = ToolContextHelper.getMetadata(toolContext, AgentToolContext.CONTEXT_KEY, AgentToolContext.class)
                .orElseThrow(() -> new IllegalStateException("缺少可信 SQL 工具上下文"));
        try {
            return datasets.readPage(datasetId, context.userId(), context.conversationId(),
                    offset == null ? 0 : offset, limit == null ? Integer.MAX_VALUE : limit);
        } catch (DatasetUnavailableException error) {
            return Map.of("status", "UNAVAILABLE", "message", "该数据集不存在、已过期或当前无权访问，无法继续读取。",
                    "guidance", "不要重复读取同一数据集；需要继续分析时根据已确认范围重新查询，范围不明确时先询问用户。");
        }
    }
}
