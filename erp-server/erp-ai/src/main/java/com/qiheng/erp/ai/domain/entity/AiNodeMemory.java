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
 * AI 节点记忆原文表
 * </p>
 *
 * <p>节点每次执行的输入、产出与反馈按发生顺序各落一条，只增不改。
 * 该表不参与节点读取，用于审计、追溯以及重新压缩。</p>
 *
 * @author Li
 * @since 2026-10-15
 */
@Data
@Accessors(chain = true)
@TableName("ai_node_memory")
@Schema(name = "AiNodeMemory", description = "AI 节点记忆原文表")
public class AiNodeMemory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "记忆 ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "所属会话 ID")
    @TableField("conversation_id")
    private Long conversationId;

    @Schema(description = "所属用户 ID")
    @TableField("user_id")
    private Long userId;

    @Schema(description = "节点编码：PLANNER、SQL_GENERATE、PYTHON_GENERATE、REPORTER、REFLECTION")
    @TableField("node_code")
    private String nodeCode;

    @Schema(description = "节点内递增序号，只增不改")
    @TableField("seq")
    private Long seq;

    @Schema(description = "JSON 原文，永不压缩改写；类别由其中的 eventType 字段表示")
    @TableField("content")
    private String content;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "逻辑删除：0 未删除，1 已删除")
    @TableLogic
    @TableField("deleted")
    private Integer deleted;
}
