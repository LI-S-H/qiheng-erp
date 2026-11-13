package com.qiheng.erp.ai.analysis;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;

/**
 * 根据成功的工具交付记录本次使用的数据集，列表预览不算使用。
 *
 * @author Li
 * @since 2026-11-10
 */
final class AnalysisDatasetInterceptor extends ToolInterceptor {
    private final ObjectMapper mapper;

    /** 注入 JSON 解析组件，用于读取工具结果与参数。 */
    AnalysisDatasetInterceptor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 工具成功后按工具类型登记本次实际使用的数据集。
     *
     * @param request 本次工具调用请求
     * @param handler 后续拦截器与工具的执行链
     * @return 工具执行结果
     */
    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        ToolCallResponse response = handler.call(request);
        if (response.isError() || response.getResult() == null) {
            return response;
        }
        var context = request.getExecutionContext().orElseThrow().config()
                .metadata(AnalysisTaskContext.CONTEXT_KEY).orElseThrow();
        try {
            JsonNode result = mapper.readTree(response.getResult());
            if (result == null || !result.isObject()) {
                return response;
            }
            recordDataset((AnalysisTaskContext) context, request, result);
        } catch (IOException | IllegalArgumentException error) {
            // 非 JSON 工具结果不作为成功数据集，原始工具响应照常返回。
        }
        return response;
    }

    /** 按工具类型登记数据集；只有真正交付数据的工具才计入。 */
    private void recordDataset(AnalysisTaskContext task, ToolCallRequest request, JsonNode result) throws IOException {
        String toolName = request.getToolName();
        if ("query_sql_data".equals(toolName)) {
            recordQueriedDataset(task, result);
        } else if ("readDataset".equals(toolName)) {
            recordReadDataset(task, request, result);
        } else if ("prepareAnalysisData".equals(toolName)) {
            recordPreparedDatasets(task, result);
        }
    }

    /** SQL 查询成功且启动了新查询时，登记本次产出的数据集。 */
    private void recordQueriedDataset(AnalysisTaskContext task, JsonNode result) {
        boolean started = !result.has("newRequestStarted") || result.path("newRequestStarted").asBoolean();
        boolean succeeded = "SUCCESS".equals(result.path("status").asText())
                || "PARTIAL".equals(result.path("status").asText());
        if (started && succeeded) {
            task.recordDataset(result.path("datasetId").asText(null));
        }
    }

    /** 读取数据集工具返回了行数据时，登记被读取的数据集。 */
    private void recordReadDataset(AnalysisTaskContext task, ToolCallRequest request, JsonNode result) throws IOException {
        if (result.has("rows")) {
            task.recordDataset(mapper.readTree(request.getArguments()).path("datasetId").asText(null));
        }
    }

    /** 准备分析数据成功后，登记准备好的全部数据集。 */
    private void recordPreparedDatasets(AnalysisTaskContext task, JsonNode result) {
        if (!"SUCCESS".equals(result.path("status").asText())) {
            return;
        }
        for (JsonNode file : result.path("files")) {
            task.recordDataset(file.path("datasetId").asText(null));
        }
    }

    /** 提供拦截器在分析任务中的名称。 */
    @Override
    public String getName() {
        return "analysis_dataset_tracking";
    }
}
