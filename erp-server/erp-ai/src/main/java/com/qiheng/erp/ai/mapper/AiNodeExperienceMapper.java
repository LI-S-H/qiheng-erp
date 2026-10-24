package com.qiheng.erp.ai.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.ai.domain.entity.AiNodeExperience;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * <p>
 * AI 节点长期经验表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-10-15
 */
public interface AiNodeExperienceMapper extends MPJBaseMapper<AiNodeExperience> {

    /**
     * 取节点已发布的经验，按生效时间倒序。
     *
     * @param nodeCode 节点编码
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
    List<AiNodeExperience> selectPublished(@Param("nodeCode") String nodeCode,
                                           @Param("limit") int limit);
}
