package com.qiheng.erp.ai.sql.tool;

import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.sql.tool.SqlDataRequirement;

import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.LinkedHashMap;
import java.util.Map;

/** 提供单次 SQL 取数，身份由服务端工具上下文提供。 */
public final class SqlQueryTool {
    private final SqlGraphService service;

    public SqlQueryTool(SqlGraphService service) {
        this.service = service;
    }

    /** 执行一次独立取数。 */
    @Tool(name = "query_sql_data", description = "按明确的业务需求执行一次只读取数，返回数据集引用和完整性状态。"
            + "对象、范围或时间不明确且会实质影响结论时先确认，不要自行扩大为全部；一次只提交一个查询需求。"
            + "返回 PARTIAL 或 NEED_INFO 时，按结果中的限制或缺失说明处理，再决定是否重新查询。")
    public Map<String, Object> query(
            @ToolParam(description = "取数需求：必填非空 goal 和 dataRequirement，可选 grain、expectedFields、purpose；包含用户已提供的条件。") SqlDataRequirement requirement,
            ToolContext toolContext) throws Exception {
        AgentToolContext context = ToolContextHelper.getMetadata(toolContext, AgentToolContext.CONTEXT_KEY, AgentToolContext.class)
                .orElseThrow(() -> new IllegalStateException("缺少可信 SQL 工具上下文"));
        if (requirement == null) {
            throw new IllegalArgumentException("请提供取数需求");
        }
        String runId = SqlGraphService.newRunId();
        Map<String, Object> result = new LinkedHashMap<>(context.businessDate() == null
                ? service.startForUser(context.userId(), context.conversationId(), runId, requirement.toMap())
                : service.startForUserAtDate(context.userId(), context.conversationId(), runId, requirement.toMap(), context.businessDate()));
        return result;
    }
}
