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

    /** 读取指定用户、会话和运行的消息，按会话顺序整理报告输入。 */
    @Select("""
        SELECT * FROM ai_message
        WHERE user_id = #{userId} AND conversation_id = #{conversationId}
          AND request_id = #{requestId} AND deleted = 0
        ORDER BY seq ASC
        """)
    List<AiMessage> selectRunMessages(@Param("userId") long userId,
                                      @Param("conversationId") long conversationId,
                                      @Param("requestId") String requestId);

    /**
     * 用户可见轮次分页：按轮次正序返回一批轮次标识，一轮不会被切开。
     *
     * @param conversationId 会话 ID
     * @param userId         用户 ID
     * @param offset         跳过的轮次数
     * @param limit          每页轮次数
     * @return 轮次标识，按该轮最早消息升序
     */
    @Select("""
        SELECT COALESCE(NULLIF(request_id, ''), CONCAT('legacy-', id)) AS turn_key
        FROM ai_message
        WHERE conversation_id = #{conversationId}
          AND user_id = #{userId}
          AND deleted = 0
        GROUP BY turn_key
        ORDER BY MIN(seq) ASC
        LIMIT #{offset}, #{limit}
        """)
    @Options(timeout = 30)
    List<String> selectVisibleTurnKeys(@Param("conversationId") Long conversationId,
                                       @Param("userId") Long userId,
                                       @Param("offset") int offset,
                                       @Param("limit") int limit);

    /**
     * 按轮次标识取整轮消息，保证同一轮的提问、过程和产物不跨页。
     *
     * @param conversationId 会话 ID
     * @param userId         用户 ID
     * @param turnKeys       轮次标识
     * @return 消息列表，按 seq 升序
     */
    @Select("""
        <script>
        SELECT *
        FROM ai_message
        WHERE conversation_id = #{conversationId}
          AND user_id = #{userId}
          AND deleted = 0
          AND COALESCE(NULLIF(request_id, ''), CONCAT('legacy-', id)) IN
          <foreach collection="turnKeys" item="key" open="(" separator="," close=")">#{key}</foreach>
        ORDER BY seq ASC
        </script>
        """)
    @Options(timeout = 30)
    List<AiMessage> selectMessagesByTurnKeys(@Param("conversationId") Long conversationId,
                                             @Param("userId") Long userId,
                                             @Param("turnKeys") List<String> turnKeys);

    /**
     * 用户可见轮次总数，用于分页。
     *
     * @param conversationId 会话 ID
     * @param userId         用户 ID
     * @return 轮次数量
     */
    @Select("""
        SELECT COUNT(DISTINCT COALESCE(NULLIF(request_id, ''), CONCAT('legacy-', id)))
        FROM ai_message
        WHERE conversation_id = #{conversationId}
          AND user_id = #{userId}
          AND deleted = 0
        """)
    @Options(timeout = 30)
    long countVisibleTurns(@Param("conversationId") Long conversationId,
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

    /** 只更新所属会话的报告过程，不改变消息顺序。 */
    @Update("""
        UPDATE ai_message SET content = #{content}
        WHERE id = #{id} AND conversation_id = #{conversationId} AND user_id = #{userId}
          AND kind = 'REPORT_STEP' AND deleted = 0
        """)
    int updateReportProcess(@Param("id") long id, @Param("conversationId") long conversationId,
                            @Param("userId") long userId, @Param("content") String content);

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
