package com.qiheng.erp.ai.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.ai.domain.entity.AiExperience;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
     * 按智能体类型读取已发布经验，经验提炼与发布流程暂未实现。
     *
     * @param agentType 智能体类型
     * @param limit     最多返回条数
     * @return 已发布经验
     */
    @Select("""
        SELECT *
        FROM ai_agent_experience
        WHERE agent_type = #{agentType}
          AND status = 'PUBLISHED'
          AND deleted = 0
        ORDER BY effective_from DESC
        LIMIT #{limit}
        """)
    @Options(timeout = 30)
    List<AiExperience> selectPublished(@Param("agentType") String agentType,
                                       @Param("limit") int limit);
}
