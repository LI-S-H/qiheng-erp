package com.qiheng.erp.ai.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.ai.domain.entity.AiExperience;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>
 * AI 长期经验表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-10-15
 */
public interface AiExperienceMapper extends MPJBaseMapper<AiExperience> {

    /**
     * 按智能体类型读取已发布经验，最新的在前。
     *
     * @param agentType 智能体类型
     * @param limit     最多返回条数
     * @return 已发布经验
     */
    @Select("""
        SELECT id, agent_type, experience_text, source_task_id, status, effective_from,
               create_time, update_time, deleted
        FROM ai_agent_experience
        WHERE agent_type = #{agentType}
          AND status = 'PUBLISHED'
          AND deleted = 0
        ORDER BY id DESC
        LIMIT #{limit}
        """)
    @Options(timeout = 30)
    List<AiExperience> selectPublished(@Param("agentType") String agentType,
                                       @Param("limit") int limit);

    /**
     * 按关键词检索已发布经验。
     *
     * @param agentType 智能体类型
     * @param keyword   关键词
     * @param limit     最多返回条数
     * @return 匹配的经验，最新的在前
     */
    @Select("""
        SELECT id, agent_type, experience_text, source_task_id, status, effective_from,
               create_time, update_time, deleted
        FROM ai_agent_experience
        WHERE agent_type = #{agentType}
          AND status = 'PUBLISHED'
          AND deleted = 0
          AND experience_text LIKE CONCAT('%', #{keyword}, '%')
        ORDER BY id DESC
        LIMIT #{limit}
        """)
    @Options(timeout = 30)
    List<AiExperience> search(@Param("agentType") String agentType,
                              @Param("keyword") String keyword,
                              @Param("limit") int limit);

    /**
     * 查询某个任务是否已经提取过经验。
     *
     * <p>不过滤 deleted：合并软删除的经验仍算已提取，避免同一任务被反复提取。</p>
     *
     * @param agentType    智能体类型
     * @param sourceTaskId 来源任务 ID
     * @return 已存在的条数
     */
    @Select("""
        SELECT COUNT(*)
        FROM ai_agent_experience
        WHERE agent_type = #{agentType}
          AND source_task_id = #{sourceTaskId}
        """)
    int countBySource(@Param("agentType") String agentType,
                      @Param("sourceTaskId") String sourceTaskId);

    /**
     * 合并后更新经验正文。
     *
     * @param id        经验 ID
     * @param agentType 智能体类型，防止跨类型误改
     * @param text      合并后的正文
     * @return 更新行数
     */
    @Update("""
        UPDATE ai_agent_experience
        SET experience_text = #{text}, update_time = NOW()
        WHERE id = #{id}
          AND agent_type = #{agentType}
          AND deleted = 0
        """)
    int updateText(@Param("id") Long id, @Param("agentType") String agentType,
                   @Param("text") String text);

    /**
     * 合并后逻辑删除不再保留的经验。
     *
     * @param id        经验 ID
     * @param agentType 智能体类型
     * @return 更新行数
     */
    @Update("""
        UPDATE ai_agent_experience
        SET deleted = 1, update_time = NOW()
        WHERE id = #{id}
          AND agent_type = #{agentType}
          AND deleted = 0
        """)
    int markDeleted(@Param("id") Long id, @Param("agentType") String agentType);
}