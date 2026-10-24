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
 * AI 节点长期经验表
 * </p>
 *
 * <p>跨任务沉淀的通用经验，按节点分类。经验不绑定具体会话或任务，
 * 否则就失去复用价值。发布状态由人工或定时任务流转。</p>
 *
 * @author Li
 * @since 2026-10-15
 */
@Data
@Accessors(chain = true)
@TableName("ai_node_experience")
@Schema(name = "AiNodeExperience", description = "AI 节点长期经验表")
public class AiNodeExperience implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "经验 ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "归属节点编码")
    @TableField("node_code")
    private String nodeCode;

    @Schema(description = "经验正文，高度精简")
    @TableField("experience_text")
    private String experienceText;

    @Schema(description = "可选证据引用")
    @TableField("evidence_refs")
    private String evidenceRefs;

    @Schema(description = "状态：CANDIDATE、PUBLISHED、REVOKED")
    @TableField("status")
    private String status;

    @Schema(description = "生效时间")
    @TableField("effective_from")
    private LocalDateTime effectiveFrom;

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
