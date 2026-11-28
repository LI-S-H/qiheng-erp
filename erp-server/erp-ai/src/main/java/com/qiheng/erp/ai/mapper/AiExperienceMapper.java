package com.qiheng.erp.ai.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.ai.domain.entity.AiExperience;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;

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

    /** 按主键分页读取全部有效经验，避免把全部正文加载到内存。 */
    @Select("""
        <script>
        SELECT id, agent_type, experience_text, source_task_id, status, effective_from, create_time, update_time, deleted
        FROM ai_agent_experience WHERE agent_type = #{agentType} AND status = 'PUBLISHED' AND deleted = 0
        <if test="afterId != null">AND id &gt; #{afterId}</if>
        ORDER BY id LIMIT #{limit}
        </script>
        """)
    List<AiExperience> selectPublishedPage(@Param("agentType") String agentType,
                                           @Param("afterId") Long afterId, @Param("limit") int limit);

    /** 提交前锁定当前有效集，检查模型处理期间是否发生并发修改。 */
    @Select("""
        <script>
        SELECT id, experience_text FROM ai_agent_experience
        WHERE agent_type = #{agentType} AND status = 'PUBLISHED' AND deleted = 0
        <if test="afterId != null">AND id &gt; #{afterId}</if>
        ORDER BY id LIMIT #{limit} FOR UPDATE
        </script>
        """)
    List<AiExperience> selectPublishedForUpdatePage(@Param("agentType") String agentType,
                                                    @Param("afterId") Long afterId, @Param("limit") int limit);

    /** 显式物理删除输入快照，不能调用带 @TableLogic 的通用删除。 */
    @Delete("""
        <script>
        DELETE FROM ai_agent_experience WHERE agent_type = #{agentType} AND status = 'PUBLISHED' AND deleted = 0
        AND id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
        </script>
        """)
    int deletePublishedByIds(@Param("agentType") String agentType, @Param("ids") List<Long> ids);

    /** 批量写入合并结果，调用方预先分配主键。 */
    @Insert("""
        <script>
        INSERT INTO ai_agent_experience
          (id, agent_type, experience_text, source_task_id, status, effective_from, create_time, update_time, deleted)
        VALUES <foreach collection="items" item="item" separator=",">
          (#{item.id}, #{item.agentType}, #{item.experienceText}, #{item.sourceTaskId}, #{item.status},
           #{item.effectiveFrom}, #{item.createTime}, #{item.updateTime}, #{item.deleted})
        </foreach>
        </script>
        """)
    int insertPublishedBatch(@Param("items") List<AiExperience> items);

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
