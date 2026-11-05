package com.qiheng.erp.ai.sql.tool;

import com.qiheng.erp.ai.sql.tool.SqlToolContext;
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
    public static final String CONTEXT_KEY = "sqlToolContext";
    private final SqlGraphService service;

    public SqlQueryTool(SqlGraphService service) {
        this.service = service;
    }

    /** 执行一次取数并在返回前清理当前工作状态。 */
    @Tool(name = "query_sql_data", description = "按明确的业务需求查询数据。提交时保留用户已明确的范围；仓库、商品或时间范围不明确且会实质影响查询目标时，不要自行写成全部，应先结合上下文确认，必要时询问用户；用户明确要求全部范围时如实提交。PARTIAL 表示已取得的数据因上限不完整，不能作为全量结果；结合限制说明调整计划。缺少必要信息时返回 NEED_INFO，可先获取信息或询问用户，再携带完整条件发起新查询。多个查询应依次执行。")
    public Map<String, Object> query(
            @ToolParam(description = "取数需求：必填非空 goal 和 dataRequirement，可选 grain、expectedFields、purpose；包含用户已提供的条件。") SqlDataRequirement requirement,
            ToolContext toolContext) throws Exception {
        SqlToolContext context = ToolContextHelper.getMetadata(toolContext, CONTEXT_KEY, SqlToolContext.class)
                .orElseThrow(() -> new IllegalStateException("缺少可信 SQL 工具上下文"));
        if (requirement == null) throw new IllegalArgumentException("请提供取数需求");
        String runId = SqlGraphService.newRunId();
        Map<String, Object> result = new LinkedHashMap<>(context.businessDate() == null
                ? service.startForUser(context.userId(), context.conversationId(), runId, requirement.toMap())
                : service.startForUserAtDate(context.userId(), context.conversationId(), runId, requirement.toMap(), context.businessDate()));
        // 已有任务表示本次请求并未启动，不能把旧结果当作新查询结果。
        if (Boolean.TRUE.equals(result.get("existing"))) {
            result.put("newRequestStarted", false);
            // 已结束的旧任务交付一次后清理，运行中或权限拒绝的任务保留供恢复。
            if (Boolean.FALSE.equals(result.get("recoveryRequired"))) {
                service.acknowledgeForUser(context.userId(), context.conversationId(), (String) result.get("sqlRunId"));
            }
        } else if (java.util.Set.of("SUCCESS", "PARTIAL", "FAILED", "NEED_INFO").contains(result.get("status"))) {
            // 采用简单交接：先清空当前工作状态，再返回工具结果。
            service.acknowledgeForUser(context.userId(), context.conversationId(), (String) result.get("sqlRunId"));
        }
        return result;
    }
}
