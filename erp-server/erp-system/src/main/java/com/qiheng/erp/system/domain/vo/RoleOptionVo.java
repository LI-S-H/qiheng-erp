package com.qiheng.erp.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "角色权限码选项分组列表")
public class RoleOptionVo {
    @Schema(description = "角色ID")
    private String roleId;

    @Schema(description = "角色名称")
    private String roleName;

    @Schema(description = "角色编码")
    private String roleCode;

    @Schema(description = "角色状态")
    private Integer status;
}
