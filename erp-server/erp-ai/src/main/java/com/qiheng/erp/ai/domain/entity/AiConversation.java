package com.qiheng.erp.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * AI 会话表
 * </p>
 *
 * <p>当前用户与经营助手之间的一段持久化会话，对应 {@code ai_conversation} 表。</p>
 *
 * @author Li
 * @since 2026-10-08
 */
@Data
@Accessors(chain = true)
@TableName("ai_conversation")
@Schema(name = "AiConversation", description = "AI 会话表")
public class AiConversation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "会话 ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "所属用户 ID")
    @TableField("user_id")
    private Long userId;

    @Schema(description = "会话标题")
    @TableField("title")
    private String title;

    @Schema(description = "会话描述")
    @TableField("description")
    private String description;

    @Schema(description = "最后一条消息时间")
    @TableField("last_message_at")
    private LocalDateTime lastMessageAt;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField("update_time")
    private LocalDateTime updateTime;

    @Schema(description = "逻辑删除：0 未删除，1 已删除")
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    @Schema(description = "乐观锁版本号")
    @Version
    @TableField("version")
    private Integer version;
}
