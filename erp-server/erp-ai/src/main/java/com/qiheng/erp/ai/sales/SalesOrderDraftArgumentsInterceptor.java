package com.qiheng.erp.ai.sales;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 在框架解析草稿参数前检查 JSON，错误时把原因交给模型修正。
 *
 * <p>等待用户处置超时的工具不能返回普通错误文本，否则模型会带着空结果继续执行，
 * 因此这里与追问一致，直接结束本轮。</p>
 *
 * @author Li
 * @since 2026-11-22
 */
public final class SalesOrderDraftArgumentsInterceptor extends ToolInterceptor {

    private final ObjectMapper mapper;

    /**
     * 构造拦截器。
     *
     * @param mapper JSON 解析工具
     */
    public SalesOrderDraftArgumentsInterceptor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    private static final java.util.Set<String> TOOL_NAMES =
            java.util.Set.of("prepare_sales_order_draft", "update_sales_order_draft");

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        if (!TOOL_NAMES.contains(request.getToolName())) {
            return handler.call(request);
        }
        try {
            var root = mapper.readTree(request.getArguments());
            // 框架把单个对象参数包在参数名下，模型也可能直接提交扁平结构，两种都要接受。
            var json = root != null && root.isObject() && root.path("request").isObject()
                    ? root.path("request") : root;
            if (json == null || !json.isObject()) {
                return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                        "销售草稿格式错误：参数必须是 JSON 对象，请修正后重试。");
            }
            if ("update_sales_order_draft".equals(request.getToolName())) {
                // 修改允许只传变化字段；传了 items 就不能是空数组。
                if (json.path("salesOrderId").asText("").isBlank()) {
                    return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                            "销售单修改格式错误：salesOrderId 不能为空，请先确认要修改的单据。");
                }
                if (json.has("items") && (!json.path("items").isArray() || json.path("items").isEmpty())) {
                    return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                            "销售单修改格式错误：items 传了就不能为空，不需要改明细时省略该字段。");
                }
            } else {
                if (!json.path("items").isArray() || json.path("items").isEmpty()) {
                    return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                            "销售草稿格式错误：items 必须是非空数组，请修正后重试。");
                }
                if (json.path("customerId").asText("").isBlank() || json.path("warehouseId").asText("").isBlank()) {
                    return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                            "销售草稿格式错误：customerId 和 warehouseId 不能为空，请先确认客户与出库仓库。");
                }
            }
            mapper.treeToValue(json, SalesOrderDraftRequest.class);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                    "销售草稿格式错误：JSON 格式或字段类型无效，请检查 items 中的产品、数量和单价后重试。");
        }
        var response = handler.call(request);
        if (SalesOrderDraftService.TIMEOUT_MARKER.equals(response.getResult())) {
            throw new IllegalStateException("等待用户确认销售草稿超时，请重新发起任务");
        }
        return response;
    }

    /**
     * 提供拦截器在主智能体中的名称。
     *
     * @return 拦截器名称
     */
    @Override
    public String getName() {
        return "prepare_sales_order_draft_arguments";
    }
}