package com.qiheng.erp.system.domain.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serial;
import java.time.LocalDateTime;
import java.io.Serializable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 权限码目录表
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sys_permission")
@Schema(description="权限码目录表")
public class SysPermission implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "权限ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "权限码，例如 system:user:query")
    @TableField("permission_code")
    private String permissionCode;

    @NotNull(message = "权限名称不能为空")
    @Schema(description = "权限名称")
    @TableField("permission_name")
    private String permissionName;

    @NotNull(message = "所属模块编码不能为空")
    @Schema(description = "所属模块编码")
    @TableField("module_code")
    private String moduleCode;

    @NotNull(message = "操作类型不能为空")
    @Schema(description = "操作类型：query/create/update/delete/manage/execute")
    @TableField("action_type")
    private String actionType;

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态必须为0或1")
    @Max(value = 1, message = "状态必须为0或1")
    @Schema(description = "状态：1启用，0禁用")
    @TableField("status")
    private Integer status;

    @NotNull(message = "排序值不能为空")
    @Schema(description = "排序值，越小越靠前")
    @TableField("sort_order")
    private Integer sortOrder;

    @NotNull(message = "权限说明不能为空")
    @Schema(description = "权限说明")
    @TableField("description")
    private String description;

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
