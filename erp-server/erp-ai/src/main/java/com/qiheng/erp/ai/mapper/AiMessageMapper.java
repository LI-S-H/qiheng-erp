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
     * 用户可见消息分页：依据展示标记返回，包括追问工具调用和回答。
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
          AND visible = 1
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
          AND visible = 1
        """)
    @Options(timeout = 30)
    long countVisibleMessages(@Param("conversationId") Long conversationId,
                              @Param("userId") Long userId);

    /** 最近一轮的持久状态；与进程内运行对象分别判断。 */
    @Select("SELECT run_status FROM ai_message WHERE conversation_id=#{conversationId} AND user_id=#{userId} AND kind='USER_INPUT' AND deleted=0 ORDER BY seq DESC LIMIT 1")
    String latestRunStatus(@Param("conversationId") long conversationId, @Param("userId") long userId);

    /**
     * 只软删除用户可见消息，不联动内部工具记录。
     */
    @Update("""
        UPDATE ai_message
        SET deleted = 1
        WHERE conversation_id = #{conversationId}
          AND user_id = #{userId}
          AND deleted = 0
          AND visible = 1
        """)
    @Options(timeout = 30)
    int softDeleteByConversation(@Param("conversationId") Long conversationId,
                                @Param("userId") Long userId);

    /** 锁定所属会话，使序号分配和消息写入在同一事务中串行。 */
    @Select("SELECT id FROM ai_conversation WHERE id = #{conversationId} AND user_id = #{userId} AND deleted = 0 FOR UPDATE")
    Long lockOwnedConversation(@Param("conversationId") long conversationId, @Param("userId") long userId);

    /** 序号必须包含逻辑删除的消息，以满足会话内唯一索引。 */
    @Select("SELECT COALESCE(MAX(seq), 0) FROM ai_message WHERE conversation_id = #{conversationId}")
    long maxSequence(@Param("conversationId") long conversationId);

    @Select("SELECT * FROM ai_message WHERE conversation_id = #{conversationId} AND message_key = #{messageKey} LIMIT 1")
    AiMessage selectByMessageKey(@Param("conversationId") long conversationId, @Param("messageKey") String messageKey);

    /** 新消息和会话活动时间在同一个事务中更新。 */
    @Update("UPDATE ai_conversation SET last_message_at = NOW() WHERE id = #{conversationId} AND user_id = #{userId} AND deleted = 0")
    int touchActivity(@Param("conversationId") long conversationId, @Param("userId") long userId);

    /** 仅首条输入可以替换默认标题，避免覆盖用户手动修改。 */
    @Update("""
        UPDATE ai_conversation SET title = #{title}, version = version + 1
        WHERE id = #{conversationId} AND user_id = #{userId} AND deleted = 0
          AND title = #{defaultTitle}
          AND (SELECT COUNT(*) FROM ai_message WHERE conversation_id = #{conversationId}
               AND kind = 'USER_INPUT' AND deleted = 0) = 1
        """)
    int nameFirstInput(@Param("userId") long userId, @Param("conversationId") long conversationId,
                       @Param("title") String title, @Param("defaultTitle") String defaultTitle);

    /** 本轮状态仅写入对应的用户输入行。 */
    @Update("""
        UPDATE ai_message SET run_status = #{status}, error_code = #{errorCode}
        WHERE conversation_id = #{conversationId} AND user_id = #{userId}
          AND request_id = #{requestId} AND kind = 'USER_INPUT' AND deleted = 0
        """)
    int updateRunStatus(@Param("userId") long userId, @Param("conversationId") long conversationId,
                        @Param("requestId") String requestId, @Param("status") String status,
                        @Param("errorCode") String errorCode);

}
