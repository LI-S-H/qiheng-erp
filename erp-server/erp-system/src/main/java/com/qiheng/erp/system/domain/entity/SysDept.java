package com.qiheng.erp.system.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;
import java.io.Serializable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 部门表
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_dept")
@Schema(description = "部门表")
public class SysDept implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "部门ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "上级部门ID，顶级为0")
    @TableField("parent_id")
    private Long parentId;

    @Schema(description = "祖级路径")
    @TableField("ancestors")
    private String ancestors;

    @Schema(description = "部门名称")
    @TableField("dept_name")
    private String deptName;

    @Schema(description = "状态：1启用，0禁用")
    @TableField("status")
    private Integer status;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField("update_time")
    private LocalDateTime updateTime;

    @Schema(description = "逻辑删除：0正常，1删除")
    @TableField("deleted")
    private Integer deleted;

    @Schema(description = "乐观锁版本号")
    @Version
    @TableField("version")
    private Integer version;


}