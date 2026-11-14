package com.qiheng.erp.ai.mapper;

import org.apache.ibatis.annotations.Delete;
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

    /**
     * 删除某个会话的全部流水；会话删除时级联清理，之后不再参与经验提炼。
     *
     * @param userId 会话所属用户
     * @param conversationId 会话编号
     * @return 删除行数
     */
    @Delete("DELETE FROM ai_agent_trace WHERE user_id = #{userId} AND conversation_id = #{conversationId}")
    int deleteByConversation(@Param("userId") long userId, @Param("conversationId") long conversationId);
}
