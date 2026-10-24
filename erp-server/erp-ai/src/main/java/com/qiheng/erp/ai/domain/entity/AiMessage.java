package com.qiheng.erp.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * AI 会话消息表
 * </p>
 *
 * <p>会话消息及其结构化展示数据，对应 {@code ai_message} 表。
 * 用户可见 content 保存 type/data 展示 JSON，历史框架消息在读取边界兼容，节点记忆独立保存。</p>
 *
 * @author Li
 * @since 2026-10-08
 */
@Data
@Accessors(chain = true)
@TableName("ai_message")
@Schema(name = "AiMessage", description = "AI 会话消息表")
public class AiMessage implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "消息 ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "所属会话 ID")
    @TableField("conversation_id")
    private Long conversationId;

    @Schema(description = "所属用户 ID")
    @TableField("user_id")
    private Long userId;

    @Schema(description = "服务端轮次标识")
    @TableField("request_id")
    private String requestId;

    @Schema(description = "会话内全局递增序号")
    @TableField("seq")
    private Long seq;

    @Schema(description = "消息种类：USER_INPUT / ASSISTANT_STEP / TOOL_RESULT / ASSISTANT_FINAL")
    @TableField("kind")
    private String kind;

    @Schema(description = "消息角色：user / assistant / tool")
    @TableField("role")
    private String role;

    @Schema(description = "用户可见 type/data JSON；旧框架消息只在读取边界兼容")
    @TableField("content")
    private String content;

    /** 仅用户输入行承载轮次状态；状态更新不改写展示消息原文。 */
    @Schema(description = "轮次状态，仅 USER_INPUT 使用：RUNNING / COMPLETED / FAILED / CANCELLED / INTERRUPTED")
    @TableField("run_status")
    private String runStatus;

    @Schema(description = "轮次失败原因编码，仅 USER_INPUT 使用，可为空")
    @TableField("error_code")
    private String errorCode;

    @Schema(description = "最终回复的历史导航摘要；NULL 表示未生成或生成失败，不替代原文")
    @TableField("history_summary")
    private String historySummary;

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
}
