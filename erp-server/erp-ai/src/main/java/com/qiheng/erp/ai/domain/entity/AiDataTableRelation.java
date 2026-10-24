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
 * AI 可用数据表关系登记表
 * </p>
 *
 * <p>ERP 全部 DDL 没有外键约束，表间关系无法从库结构推导，只能人工声明。
 * 登记时只写「固定指向」的关系：多态弱关联（靠 {@code source_type} 决定指向哪张表）
 * 与指向不对 AI 开放的 {@code sys_user} 的 {@code *_by_id} 都不登记——
 * 前者写不成固定关系，后者会让模型去 JOIN 一个 Schema 里不存在的表。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
@Data
@Accessors(chain = true)
@TableName(value = "ai_data_table_relation", autoResultMap = true)
@Schema(name = "AiDataTableRelation", description = "AI 可用数据表关系登记表")
public class AiDataTableRelation implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "主键 ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "来源表")
    @TableField("from_table")
    private String fromTable;

    @Schema(description = "来源列")
    @TableField("from_column")
    private String fromColumn;

    @Schema(description = "目标表")
    @TableField("to_table")
    private String toTable;

    @Schema(description = "目标列")
    @TableField("to_column")
    private String toColumn;

    @Schema(description = "基数：1:1、N:1、1:N")
    @TableField("cardinality")
    private String cardinality;

    @Schema(description = "关系说明，给模型看")
    @TableField("description")
    private String description;

    @Schema(description = "开关：1 生效，0 停用")
    @TableField("status")
    private Integer status;

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
