package com.qiheng.erp.system.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serial;
import java.time.LocalDateTime;
import java.io.Serializable;
import java.util.List;

import com.qiheng.erp.common.handler.JsonStringListTypeHandler;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 角色表
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "sys_role", autoResultMap = true)
@Schema( description="角色表")
public class SysRole implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema( description="主键ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema( description="角色编码")
    @TableField("role_code")
    @NotNull(message = "角色编码不能为空")
    private String roleCode;

    @Schema( description="角色名称")
    @TableField("role_name")
    @NotNull(message = "角色名称不能为空")
    private String roleName;

    @Schema( description="粗粒度权限码列表")
    @TableField(value = "permission_codes", typeHandler = JsonStringListTypeHandler.class)
    @NotNull(message = "粗粒度权限码列表不能为空")
    private List<String> permissionCodes;

    @Schema( description="状态：1启用，0禁用")
    @TableField("status")
    @NotNull(message = "状态不能为空")
    private Integer status;

    @Schema( description="创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema( description="更新时间")
    @TableField("update_time")
    private LocalDateTime updateTime;

    @Schema( description="逻辑删除：0正常，1删除")
    @TableField("deleted")
    private Integer deleted;

    @Schema( description="备注")
    @TableField("remark")
    private String remark;

    @Schema( description="乐观锁版本号")
    @Version
    @TableField("version")
    private Integer version;


}