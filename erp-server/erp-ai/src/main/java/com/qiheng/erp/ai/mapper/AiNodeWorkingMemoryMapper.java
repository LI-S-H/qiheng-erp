package com.qiheng.erp.ai.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.ai.domain.entity.AiNodeWorkingMemory;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>
 * AI 节点工作记忆表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-10-15
 */
public interface AiNodeWorkingMemoryMapper extends MPJBaseMapper<AiNodeWorkingMemory> {

    /**
     * 取节点当前最大排序号，无记录返回 0。
     *
     * <p>不过滤逻辑删除记录：压缩会逻辑删除早期条目，
     * 被删除的行仍占用唯一索引，跳过会导致重复键冲突。</p>
     *
     * @param conversationId 会话 ID
     * @param nodeCode       节点编码
     * @return 当前最大排序号
     */
    @Select("""
        SELECT COALESCE(MAX(sort_order), 0)
        FROM ai_node_working_memory
        WHERE conversation_id = #{conversationId}
          AND node_code = #{nodeCode}
        """)
    @Options(timeout = 30)
    long selectMaxSortOrder(@Param("conversationId") Long conversationId,
                            @Param("nodeCode") String nodeCode);

    /**
     * 读取节点工作记忆，节点获取历史记忆的唯一入口。
     *
     * <p>返回结果已包含压缩摘要块，调用方不需要判断哪些条目被压缩过。</p>
     *
     * @param conversationId 会话 ID
     * @param nodeCode       节点编码
     * @return 按排序号升序的工作记忆
     */
    @Select("""
        SELECT *
        FROM ai_node_working_memory
        WHERE conversation_id = #{conversationId}
          AND node_code = #{nodeCode}
          AND deleted = 0
        ORDER BY sort_order ASC
        """)
    @Options(timeout = 30)
    List<AiNodeWorkingMemory> selectWorkingMemory(@Param("conversationId") Long conversationId,
                                                  @Param("nodeCode") String nodeCode);

    /** 用户范围由服务端身份确定，防止节点跨用户读取工作记忆。 */
    @Select("""
        SELECT * FROM ai_node_working_memory
        WHERE conversation_id = #{conversationId}
          AND user_id = #{userId}
          AND node_code = #{nodeCode}
          AND deleted = 0
        ORDER BY sort_order ASC
        """)
    @Options(timeout = 30)
    List<AiNodeWorkingMemory> selectOwnedWorkingMemory(@Param("conversationId") Long conversationId,
                                                       @Param("userId") Long userId,
                                                       @Param("nodeCode") String nodeCode);

    /** 仅替换读取到的旧窗口，新写入的条目不受影响。 */
    @Update("""
        <script>
        UPDATE ai_node_working_memory SET deleted = 1
        WHERE conversation_id = #{conversationId}
          AND user_id = #{userId}
          AND node_code = #{nodeCode}
          AND deleted = 0
          AND id IN
          <foreach collection="ids" item="id" open="(" separator="," close=")">
            #{id}
          </foreach>
        </script>
        """)
    @Options(timeout = 30)
    int softDeleteWindow(@Param("conversationId") Long conversationId,
                         @Param("userId") Long userId,
                         @Param("nodeCode") String nodeCode,
                         @Param("ids") List<Long> ids);
}
