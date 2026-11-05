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
 * AI 长期经验表
 * </p>
 *
 * <p>经验不绑定具体会话或任务。本轮只调整表名与映射；原 node_code 分类字段
 * 暂时保留以承接历史数据，新的经验分类与保存方式后续设计。</p>
 *
 * @author Li
 * @since 2026-10-15
 */
@Data
@Accessors(chain = true)
@TableName("ai_node_experience")
@Schema(name = "AiExperience", description = "AI 长期经验表")
public class AiExperience implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "经验 ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "历史分类编码，暂沿用 node_code，后续分类方式待设计")
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
