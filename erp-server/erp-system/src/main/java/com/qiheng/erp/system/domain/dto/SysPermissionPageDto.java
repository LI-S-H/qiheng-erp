package com.qiheng.erp.system.domain.dto;

import com.qiheng.erp.common.dto.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "权限码分页查询请求")
public class SysPermissionPageDto extends PageQuery {

    @Schema(description = "权限码（包含匹配）")
    private String permissionCode;

    @Schema(description = "权限名称（包含匹配）")
    private String permissionName;

    @Schema(description = "所属模块编码")
    private String moduleCode;

    @Schema(description = "操作类型：query/create/update/delete/manage/execute")
    private String actionType;

    @Schema(description = "状态：1启用，0禁用")
    private Integer status;
}