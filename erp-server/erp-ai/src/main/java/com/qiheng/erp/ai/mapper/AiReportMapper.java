package com.qiheng.erp.ai.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 正式报告写入和按会话读取。卡片查询只取元信息。 */
public interface AiReportMapper {
    record CardRow(String reportId, String requestId, String toolCallId, String title) {}

    record DetailRow(String reportId, String requestId, String toolCallId, String title, String content) {}

    @Select("""
            SELECT report_id, request_id, tool_call_id, title
            FROM ai_report
            WHERE user_id = #{userId} AND conversation_id = #{conversationId}
              AND request_id = #{requestId} AND tool_call_id = #{toolCallId}
            """)
    CardRow getByCall(@Param("userId") long userId, @Param("conversationId") long conversationId,
                      @Param("requestId") String requestId, @Param("toolCallId") String toolCallId);

    /** 使用与唯一索引相同的数据库排序规则判定同轮重名。 */
    @Select("""
            SELECT EXISTS(
                SELECT 1 FROM ai_report
                WHERE conversation_id = #{conversationId} AND request_id = #{requestId}
                  AND title = #{title}
            )
            """)
    boolean titleExists(@Param("conversationId") long conversationId, @Param("requestId") String requestId,
                        @Param("title") String title);

    @Insert("""
            INSERT INTO ai_report (report_id, user_id, conversation_id, request_id, tool_call_id, title, content)
            VALUES (#{reportId}, #{userId}, #{conversationId}, #{requestId}, #{toolCallId}, #{title}, #{content})
            """)
    int insert(@Param("reportId") String reportId, @Param("userId") long userId,
               @Param("conversationId") long conversationId, @Param("requestId") String requestId,
               @Param("toolCallId") String toolCallId, @Param("title") String title,
               @Param("content") String content);

    @Select("""
            <script>
            SELECT r.report_id, r.request_id, r.tool_call_id, r.title
            FROM ai_report r
            JOIN ai_conversation c ON c.id = r.conversation_id AND c.user_id = r.user_id AND c.deleted = 0
            WHERE r.user_id = #{userId} AND r.conversation_id = #{conversationId}
            <if test="!requestIds.isEmpty()">
              AND r.request_id IN
              <foreach collection="requestIds" item="requestId" open="(" separator="," close=")">
                #{requestId}
              </foreach>
            </if>
            ORDER BY r.created_at DESC, r.report_id DESC
            <if test="requestIds.isEmpty()">
              LIMIT 200
            </if>
            </script>
            """)
    @Options(timeout = 30)
    List<CardRow> listCards(@Param("userId") long userId, @Param("conversationId") long conversationId,
                            @Param("requestIds") List<String> requestIds);

    @Select("""
            SELECT r.report_id, r.request_id, r.tool_call_id, r.title, r.content
            FROM ai_report r
            JOIN ai_conversation c ON c.id = r.conversation_id AND c.user_id = r.user_id AND c.deleted = 0
            WHERE r.user_id = #{userId} AND r.conversation_id = #{conversationId} AND r.report_id = #{reportId}
            """)
    @Options(timeout = 30)
    DetailRow getOwned(@Param("userId") long userId, @Param("conversationId") long conversationId,
                       @Param("reportId") String reportId);
}
