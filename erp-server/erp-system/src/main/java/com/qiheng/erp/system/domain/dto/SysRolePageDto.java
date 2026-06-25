package com.qiheng.erp.system.domain.dto;

import com.qiheng.erp.common.dto.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "角色分页查询请求")
public class SysRolePageDto extends PageQuery {

    @Schema(description = "角色编码（模糊查询）")
    private String roleCode;

    @Schema(description = "角色名称（模糊查询）")
    private String roleName;

    @Schema(description = "状态：1启用，0禁用")
    private Integer status;
}