package com.qiheng.erp.ai.mapper;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 追加智能体内部任务流水。 */
public interface AiAgentTraceMapper {

    /** 一次可提取经验的任务；eventCount 为流水条数。 */
    record ExperienceTask(String agentType, String taskId, Long userId, Long conversationId,
                          Long eventCount, int attemptCount) {
        public ExperienceTask(String agentType, String taskId, Long userId, Long conversationId, Long eventCount) {
            this(agentType, taskId, userId, conversationId, eventCount, 0);
        }
    }

    /** 一条流水事件；content 为原始 JSON 字符串。 */
    record TraceEvent(Long id, String eventType, String content) {
    }
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

    /**
     * 按任务读取全部流水，按写入顺序返回。
     *
     * @param userId         所属用户
     * @param conversationId 所属会话
     * @param taskId         任务 ID
     * @param agentType      智能体类型
     * @return 流水事件
     */
    @Select("""
        SELECT id, event_type, content
        FROM ai_agent_trace
        WHERE user_id = #{userId}
          AND conversation_id = #{conversationId}
          AND task_id = #{taskId}
          AND agent_type = #{agentType}
        ORDER BY id
        """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = Long.class),
            @Arg(column = "event_type", javaType = String.class),
            @Arg(column = "content", javaType = String.class)
    })
    List<TraceEvent> selectEvents(@Param("userId") long userId,
                                  @Param("conversationId") long conversationId,
                                  @Param("taskId") String taskId,
                                  @Param("agentType") String agentType);
}
