package com.qiheng.erp.system.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "权限码分页查询响应")
public class SysPermissionVo {

    @Schema(description = "权限ID")
    private Long permissionId;

    @Schema(description = "权限码")
    private String permissionCode;

    @Schema(description = "权限名称")
    private String permissionName;

    @Schema(description = "所属模块编码")
    private String moduleCode;

    @Schema(description = "操作类型")
    private String actionType;

    @Schema(description = "状态：1启用，0禁用")
    private Integer status;

    @Schema(description = "排序值")
    private Integer sortOrder;

    @Schema(description = "权限说明")
    private String description;

    @Schema(description = "引用该权限码的角色数量")
    private Integer roleCount;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}