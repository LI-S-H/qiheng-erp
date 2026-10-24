package com.qiheng.erp.ai.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * <p>
 * AI 消息 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-10-08
 */
public interface AiMessageMapper extends MPJBaseMapper<AiMessage> {

    /**
     * 用户可见消息分页：只返回 USER_INPUT 与 ASSISTANT_FINAL。
     *
     * <p>节点中间步骤和工具结果不会出现在这里。</p>
     *
     * @param conversationId 会话 ID
     * @param userId         用户 ID
     * @param offset         分页偏移
     * @param limit          分页大小
     * @return 消息列表，按 seq 升序
     */
    @Select("""
        SELECT *
        FROM ai_message
        WHERE conversation_id = #{conversationId}
          AND user_id = #{userId}
          AND deleted = 0
          AND kind IN ('USER_INPUT', 'ASSISTANT_FINAL')
        ORDER BY seq ASC
        LIMIT #{offset}, #{limit}
        """)
    @Options(timeout = 30)
    List<AiMessage> selectVisibleMessages(@Param("conversationId") Long conversationId,
                                        @Param("userId") Long userId,
                                        @Param("offset") int offset,
                                        @Param("limit") int limit);

    /**
     * 用户可见消息总数，用于分页。
     */
    @Select("""
        SELECT COUNT(*)
        FROM ai_message
        WHERE conversation_id = #{conversationId}
          AND user_id = #{userId}
          AND deleted = 0
          AND kind IN ('USER_INPUT', 'ASSISTANT_FINAL')
        """)
    @Options(timeout = 30)
    long countVisibleMessages(@Param("conversationId") Long conversationId,
                              @Param("userId") Long userId);

    /**
     * 取全部已占用序号，包括旧工具消息及软删除行；调用方已持有会话锁。
     */
    @Select("""
        SELECT COALESCE(MAX(seq), 0)
        FROM ai_message
        WHERE conversation_id = #{conversationId}
          AND user_id = #{userId}
        """)
    @Options(timeout = 30)
    long selectVisibleMaxSeq(@Param("conversationId") Long conversationId,
                             @Param("userId") Long userId);

    /**
     * 只软删除用户可见消息，不联动节点记忆与内部工具记录。
     */
    @Update("""
        UPDATE ai_message
        SET deleted = 1
        WHERE conversation_id = #{conversationId}
          AND user_id = #{userId}
          AND deleted = 0
          AND kind IN ('USER_INPUT', 'ASSISTANT_FINAL')
        """)
    @Options(timeout = 30)
    int softDeleteByConversation(@Param("conversationId") Long conversationId,
                                @Param("userId") Long userId);

    /** 最近输入只用于判定当前轮次，展示历史仍由独立分页接口读取。 */
    @Select("""
        SELECT * FROM ai_message
        WHERE conversation_id = #{conversationId} AND user_id = #{userId}
          AND kind = 'USER_INPUT' AND deleted = 0
        ORDER BY seq DESC LIMIT 1
        """)
    @Options(timeout = 30)
    AiMessage selectLatestInput(@Param("conversationId") long conversationId, @Param("userId") long userId);

    @Select("""
        SELECT * FROM ai_message
        WHERE conversation_id = #{conversationId} AND user_id = #{userId}
          AND request_id = #{requestId} AND kind = 'ASSISTANT_FINAL' AND deleted = 0
        ORDER BY seq DESC LIMIT 1
        """)
    @Options(timeout = 30)
    AiMessage selectFinalByRequest(@Param("conversationId") long conversationId,
                                 @Param("userId") long userId, @Param("requestId") String requestId);

    /** 新协议问题直接按 pauseId 查找；旧消息未保存暂停标识时不猜测关联。 */
    @Select("""
        SELECT * FROM ai_message
        WHERE conversation_id = #{conversationId} AND user_id = #{userId}
          AND kind = 'ASSISTANT_FINAL' AND deleted = 0
          AND JSON_UNQUOTE(JSON_EXTRACT(content, '$.type')) = 'CLARIFICATION'
          AND JSON_UNQUOTE(JSON_EXTRACT(content, '$.data.pauseId')) = #{pauseId}
        ORDER BY seq DESC LIMIT 1
        """)
    @Options(timeout = 30)
    AiMessage selectClarification(@Param("conversationId") long conversationId,
                                  @Param("userId") long userId, @Param("pauseId") String pauseId);

    /** 只改变用户输入行的轮次终态，避免失败处理覆盖已经提交的成功状态。 */
    @Update("""
        UPDATE ai_message SET run_status = #{status}, error_code = #{errorCode}
        WHERE conversation_id = #{conversationId} AND user_id = #{userId}
          AND request_id = #{requestId} AND kind = 'USER_INPUT'
          AND deleted = 0 AND run_status = 'RUNNING'
        """)
    @Options(timeout = 30)
    int finishRequest(@Param("conversationId") long conversationId, @Param("userId") long userId,
                      @Param("requestId") String requestId, @Param("status") String status,
                      @Param("errorCode") String errorCode);
}
