package com.qiheng.erp.ai.sql.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.trace.AiAgentTraceRecorder;

import java.util.Map;

/**
 * 取数子图的流水写入支持；按节点所属智能体建立记录器。
 *
 * @author Li
 * @since 2026-11-13
 */
public record SqlTraceSupport(AiAgentTraceMapper traces, ObjectMapper mapper) {

    /**
     * 按当前工作状态建立取数子图的流水记录器。
     *
     * @param work 当前取数工作状态，提供归属用户、会话和执行编号
     * @param agentType 智能体类型：SQL_GENERATE 或 SQL_INSPECT
     * @return 该节点的流水记录器
     */
    public AiAgentTraceRecorder create(Map<String, Object> work, String agentType) {
        long userId = Long.parseLong(String.valueOf(work.get("userId")));
        long conversationId = Long.parseLong(String.valueOf(work.get("conversationId")));
        String taskId = String.valueOf(work.get("sqlRunId"));
        return new AiAgentTraceRecorder(traces, mapper,
                new AiAgentTraceRecorder.Scope(userId, conversationId, taskId, agentType));
    }
}
