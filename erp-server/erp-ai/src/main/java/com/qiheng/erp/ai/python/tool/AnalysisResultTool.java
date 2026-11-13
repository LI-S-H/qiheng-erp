package com.qiheng.erp.ai.python.tool;

import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 查询已回传到服务器的分析结果，不依赖分析沙箱。
 *
 * @author Li
 * @since 2026-11-10
 */
@Component
public final class AnalysisResultTool {
    public static final String CONTEXT_TASK_ID = "analysisTaskId";
    private final AnalysisResultStore results;

    /** 复用结果存储的所有权和来源权限检查。 */
    public AnalysisResultTool(AnalysisResultStore results) {
        this.results = results;
    }

    /** 获取一次分析任务的结果元信息，不读取文件正文。 */
    @Tool(name = "listAnalysisResults", description = "查询一次分析任务已保存的文件和文本结果元信息，不读取正文。")
    public Map<String, Object> listAnalysisResults(
            @ToolParam(required = false, description = "分析任务 ID；分析任务内部可省略并使用当前任务，其他场景须提供")
            String analysisTaskId, ToolContext context) throws IOException {
        AgentToolContext identity = ToolContextHelper.getMetadata(context, AgentToolContext.CONTEXT_KEY, AgentToolContext.class)
                .orElseThrow(() -> new IllegalStateException("缺少可信工具上下文"));
        String taskId = analysisTaskId;
        if (taskId == null || taskId.isBlank()) {
            taskId = ToolContextHelper.getMetadata(context, CONTEXT_TASK_ID, String.class).orElse(null);
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("请提供要查询的分析任务 ID");
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (var item : results.list(identity, taskId)) {
            var info = new LinkedHashMap<String, Object>();
            info.put("resultId", item.getResultId());
            info.put("name", item.getName());
            info.put("resultType", item.getResultType());
            info.put("fileType", item.getFileType());
            info.put("size", item.getFileSize());
            items.add(info);
        }
        return Map.of("analysisTaskId", taskId, "results", items);
    }

    /** 按结果 ID 完整读取服务器上的文本文件。 */
    @Tool(name = "readAnalysisResult", description = "按结果 ID 读取当前会话已保存的完整文本、CSV、JSON 或 JSONL 文件。")
    public Map<String, Object> readAnalysisResult(
            @ToolParam(description = "已保存的分析结果 ID") String resultId,
            ToolContext context) throws IOException {
        AgentToolContext identity = ToolContextHelper.getMetadata(context, AgentToolContext.CONTEXT_KEY, AgentToolContext.class)
                .orElseThrow(() -> new IllegalStateException("缺少可信工具上下文"));
        var text = results.readText(resultId, identity);
        var response = new LinkedHashMap<String, Object>();
        response.put("resultId", resultId);
        response.put("name", text.name());
        response.put("fileType", text.fileType());
        response.put("content", text.content());
        return response;
    }
}
