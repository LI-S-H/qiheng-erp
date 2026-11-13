package com.qiheng.erp.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;

/** 追加智能体内部任务流水。 */
public interface AiAgentTraceMapper {
    /** 流水只追加；id 由数据库分配，便于按写入顺序查阅。 */
    @Insert("""
            INSERT INTO ai_agent_trace
                (user_id, conversation_id, task_id, agent_type, event_type, content)
            VALUES (#{userId}, #{conversationId}, #{taskId}, #{agentType}, #{eventType}, #{content})
            """)
    void insert(@Param("userId") long userId, @Param("conversationId") long conversationId,
                @Param("taskId") String taskId, @Param("agentType") String agentType,
                @Param("eventType") String eventType, @Param("content") String content);
}
