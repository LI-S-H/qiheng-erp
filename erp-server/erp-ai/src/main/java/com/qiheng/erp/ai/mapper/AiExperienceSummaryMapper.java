package com.qiheng.erp.ai.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 经验目录摘要：每个智能体一行，注入时只读这一行。
 *
 * @author Li
 * @since 2026-11-20
 */
public interface AiExperienceSummaryMapper {

    /**
     * 读取某个智能体的经验目录摘要。
     *
     * @param agentType 智能体类型
     * @return 摘要文本；没有时返回 null
     */
    @Select("""
        SELECT summary
        FROM ai_agent_experience_summary
        WHERE agent_type = #{agentType}
        """)
    String selectSummary(@Param("agentType") String agentType);

    /**
     * 写入或覆盖目录摘要。
     *
     * @param agentType 智能体类型
     * @param summary   摘要文本
     * @return 影响行数
     */
    @Insert("""
        INSERT INTO ai_agent_experience_summary (agent_type, summary)
        VALUES (#{agentType}, #{summary})
        ON DUPLICATE KEY UPDATE summary = VALUES(summary)
        """)
    int upsert(@Param("agentType") String agentType, @Param("summary") String summary);
}