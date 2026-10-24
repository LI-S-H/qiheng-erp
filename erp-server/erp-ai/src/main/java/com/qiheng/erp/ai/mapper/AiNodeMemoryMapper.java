package com.qiheng.erp.ai.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.ai.domain.entity.AiNodeMemory;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * AI 节点记忆原文表 Mapper 接口
 * </p>
 *
 * <p>该表只增不改，读写通过 {@code NodeMemoryStore} 统一入口，
 * 业务代码不直接调用本 Mapper。</p>
 *
 * @author Li
 * @since 2026-10-15
 */
public interface AiNodeMemoryMapper extends MPJBaseMapper<AiNodeMemory> {

    /**
     * 取节点当前最大序号，无记录返回 0。
     *
     * <p>不过滤逻辑删除记录：序号在会话内不复用，
     * 逻辑删除的行仍占用唯一索引，跳过会导致重复键冲突。</p>
     *
     * @param conversationId 会话 ID
     * @param nodeCode       节点编码
     * @return 当前最大序号
     */
    @Select("""
        SELECT COALESCE(MAX(seq), 0)
        FROM ai_node_memory
        WHERE conversation_id = #{conversationId}
          AND node_code = #{nodeCode}
        """)
    @Options(timeout = 30)
    long selectMaxSeq(@Param("conversationId") Long conversationId,
                      @Param("nodeCode") String nodeCode);
}
