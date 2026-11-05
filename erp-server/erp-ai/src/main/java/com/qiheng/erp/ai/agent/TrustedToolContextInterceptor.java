package com.qiheng.erp.ai.agent;

import com.qiheng.erp.ai.clarification.ClarificationService;

import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.ai.sql.tool.SqlToolContext;

import java.util.HashMap;

/** 将服务端身份注入工具上下文，避免依赖工具执行线程的登录信息。 */
public final class TrustedToolContextInterceptor extends ToolInterceptor {
    static final String TRUSTED_CONTEXT = "trustedSqlContext";

    @Override
    public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
        SqlToolContext trusted = (SqlToolContext) request.getExecutionContext().orElseThrow()
                .config().metadata(TRUSTED_CONTEXT).orElseThrow();
        var context = new HashMap<>(request.getContext());
        context.put(SqlQueryTool.CONTEXT_KEY, trusted);
        request.getExecutionContext().orElseThrow().config().metadata(ClarificationService.EVENT_KEY)
                .ifPresent(value -> context.put(ClarificationService.EVENT_KEY, value));
        // 框架复制构建器不会保留执行上下文，后续拦截器仍需读取恢复配置。
        return handler.call(ToolCallRequest.builder(request)
                .context(context).executionContext(request.getExecutionContext().orElseThrow()).build());
    }

    @Override
    public String getName() {
        return "trusted_tool_context";
    }
}
