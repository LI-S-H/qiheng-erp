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
 * 用户表
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_user")
@Schema(description="用户表")
public class SysUser implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "用户ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "登录账号")
    @TableField("username")
    private String username;

    @Schema(description = "密码哈希")
    @TableField("password_hash")
    private String passwordHash;

    @Schema(description = "用户姓名")
    @TableField("real_name")
    private String realName;

    @Schema(description = "所属部门ID")
    @TableField("dept_id")
    private Long deptId;

    @Schema(description = "是否超级管理员：1是，0否")
    @TableField("is_admin")
    private Integer isAdmin;

    @Schema(description = "状态：1启用，0禁用")
    @TableField("status")
    private Integer status;

    @Schema(description = "最近登录时间")
    @TableField("last_login_at")
    private LocalDateTime lastLoginAt;

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
