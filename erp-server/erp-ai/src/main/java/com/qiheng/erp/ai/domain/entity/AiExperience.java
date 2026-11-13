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
 * <p>经验不绑定具体会话或任务，按智能体类型分类；证据引用指向 {@code ai_agent_trace} 的流水编号，
 * 形成"流水 → 经验"的证据链。经验提炼与发布流程暂未实现。</p>
 *
 * @author Li
 * @since 2026-10-15
 */
@Data
@Accessors(chain = true)
@TableName("ai_agent_experience")
@Schema(name = "AiExperience", description = "AI 长期经验表")
public class AiExperience implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "经验 ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "智能体类型：MAIN / ANALYSIS / SQL_GENERATE / SQL_INSPECT")
    @TableField("agent_type")
    private String agentType;

    @Schema(description = "经验正文，高度精简")
    @TableField("experience_text")
    private String experienceText;

    @Schema(description = "经验依据的智能体流水编号列表")
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
