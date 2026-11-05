package com.qiheng.erp.ai.clarification;

import com.qiheng.erp.ai.clarification.ClarificationService;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.clarification.AskUserTool.Question;

/** 在框架解析工具参数前检查追问 JSON，错误时将原因交给模型修正。 */
public final class AskUserArgumentsInterceptor extends ToolInterceptor {
    private final ObjectMapper mapper;

    public AskUserArgumentsInterceptor(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        if (!"ask_user".equals(request.getToolName())) {
            return handler.call(request);
        }
        try {
            var json = mapper.readTree(request.getArguments());
            if (json == null || !json.isObject() || !json.path("questions").isArray()) {
                return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                        "追问格式错误：questions 必须是问题数组，请修正后重试。");
            }
            for (var question : json.path("questions")) {
                mapper.treeToValue(question, Question.class);
            }
        } catch (JsonProcessingException | IllegalArgumentException error) {
            return ToolCallResponse.of(request.getToolCallId(), request.getToolName(),
                    "追问格式错误：JSON 格式或字段类型无效，请检查 questions 和 type 后重试。");
        }
        var response = handler.call(request);
        // 工具异常默认会变成模型可见的错误消息，超时需要直接结束本轮。
        if (ClarificationService.TIMEOUT_MARKER.equals(response.getResult())) {
            throw new IllegalStateException("等待用户回答超时，请重新发起任务");
        }
        return response;
    }

    @Override
    public String getName() { return "ask_user_arguments"; }
}
