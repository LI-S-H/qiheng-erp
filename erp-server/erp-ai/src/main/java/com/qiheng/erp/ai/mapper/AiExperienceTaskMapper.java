package com.qiheng.erp.ai.mapper;

import com.qiheng.erp.ai.mapper.AiAgentTraceMapper.ExperienceTask;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 记录任务提取进度；经验重整不会影响已处理任务的判断。 */
public interface AiExperienceTaskMapper {
    @Insert("""
        INSERT IGNORE INTO ai_agent_experience_task
            (user_id, conversation_id, agent_type, task_id, status, attempt_count)
        VALUES (#{userId}, #{conversationId}, #{agentType}, #{taskId}, 'PENDING', 0)
        """)
    int register(@Param("userId") long userId, @Param("conversationId") long conversationId,
                 @Param("agentType") String agentType, @Param("taskId") String taskId);

    @Select("""
        SELECT agent_type, task_id, user_id, conversation_id, 0 AS event_count, attempt_count
        FROM ai_agent_experience_task WHERE status = 'PENDING'
        ORDER BY create_time, id
        """)
    @ConstructorArgs({
        @Arg(column = "agent_type", javaType = String.class),
        @Arg(column = "task_id", javaType = String.class),
        @Arg(column = "user_id", javaType = Long.class),
        @Arg(column = "conversation_id", javaType = Long.class),
        @Arg(column = "event_count", javaType = Long.class),
        @Arg(column = "attempt_count", javaType = int.class)
    })
    List<ExperienceTask> pending();

    @Update("""
        UPDATE ai_agent_experience_task SET status = 'PROCESSING', update_time = NOW()
        WHERE user_id = #{userId} AND conversation_id = #{conversationId}
          AND agent_type = #{agentType} AND task_id = #{taskId} AND status = 'PENDING'
        """)
    int claim(@Param("userId") long userId, @Param("conversationId") long conversationId,
              @Param("agentType") String agentType, @Param("taskId") String taskId);

    @Update("""
        UPDATE ai_agent_experience_task SET status = #{status},
          attempt_count = CASE WHEN #{status} = 'COMPLETED' THEN attempt_count ELSE #{attemptCount} END,
          update_time = NOW()
        WHERE user_id = #{userId} AND conversation_id = #{conversationId}
          AND agent_type = #{agentType} AND task_id = #{taskId} AND status = 'PROCESSING'
        """)
    int finish(@Param("userId") long userId, @Param("conversationId") long conversationId,
               @Param("agentType") String agentType, @Param("taskId") String taskId,
               @Param("status") String status, @Param("attemptCount") int attemptCount);

    @Update("""
        UPDATE ai_agent_experience_task SET status = 'PENDING', update_time = NOW()
        WHERE user_id = #{userId} AND conversation_id = #{conversationId}
          AND agent_type = #{agentType} AND task_id = #{taskId} AND status = 'PROCESSING'
        """)
    int release(@Param("userId") long userId, @Param("conversationId") long conversationId,
                @Param("agentType") String agentType, @Param("taskId") String taskId);

    @Update("UPDATE ai_agent_experience_task SET status = 'PENDING', update_time = NOW() WHERE status = 'PROCESSING'")
    int recoverProcessing();
}
