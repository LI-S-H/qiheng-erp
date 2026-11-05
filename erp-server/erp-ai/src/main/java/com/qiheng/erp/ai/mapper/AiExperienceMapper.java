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
     * 按历史分类读取已发布经验，分类模型在后续经验设计时调整。
     *
     * @param nodeCode 暂沿用的历史分类编码
     * @param limit    最多返回条数
     * @return 已发布经验
     */
    @Select("""
        SELECT *
        FROM ai_node_experience
        WHERE node_code = #{nodeCode}
          AND status = 'PUBLISHED'
          AND deleted = 0
        ORDER BY effective_from DESC
        LIMIT #{limit}
        """)
    @Options(timeout = 30)
    List<AiExperience> selectPublished(@Param("nodeCode") String nodeCode,
                                           @Param("limit") int limit);
}
