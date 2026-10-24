package com.qiheng.erp.ai.mapper;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * <p>
 * AI 会话表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-10-08
 */
public interface AiConversationMapper extends MPJBaseMapper<AiConversation> {

    /**
     * 历史读取只校验归属和存活状态，不再持有旧聊天轮次的写锁。
     */
    @Select("SELECT * FROM ai_conversation WHERE id=#{id} AND user_id=#{userId} AND deleted=0")
    @Options(timeout = 30)
    AiConversation selectOwned(@Param("id") Long id, @Param("userId") Long userId);

    /**
     * 当前用户的会话列表，按最近一次活动倒序。
     *
     * <p>刚建好、还没发过消息的会话按创建时间参与排序，否则会在列表里沉底。</p>
     */
    @Select("""
        SELECT * FROM ai_conversation
        WHERE user_id = #{userId} AND deleted = 0
        ORDER BY COALESCE(last_message_at, create_time) DESC, id DESC
        LIMIT 50
        """)
    @Options(timeout = 30)
    List<AiConversation> selectOverviewByUser(@Param("userId") Long userId);

    /**
     * 追加消息后刷新会话的最近活动时间，会话列表据此排序。
     */
    @Update("""
        UPDATE ai_conversation
        SET last_message_at = #{lastMessageAt},
            version = version + 1
        WHERE id = #{id} AND deleted = 0
        """)
    @Options(timeout = 30)
    int touchLastMessageAt(@Param("id") Long id, @Param("lastMessageAt") LocalDateTime lastMessageAt);

    /**
     * 只改标题：首条需求自动命名和用户手动重命名共用。
     */
    @Update("""
        UPDATE ai_conversation
        SET title = #{title},
            version = version + 1
        WHERE id = #{id} AND deleted = 0
        """)
    @Options(timeout = 30)
    int updateTitle(@Param("id") Long id, @Param("title") String title);

    /**
     * 软删除会话，乐观锁自增。
     */
    @Update("""
        UPDATE ai_conversation
        SET deleted = 1, version = version + 1
        WHERE id = #{id} AND deleted = 0
        """)
    @Options(timeout = 30)
    int softDelete(@Param("id") Long id);
}
