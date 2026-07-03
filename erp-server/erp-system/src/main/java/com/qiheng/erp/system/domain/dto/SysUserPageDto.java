package com.qiheng.erp.system.domain.dto;

import com.qiheng.erp.common.dto.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "用户分页查询请求")
public class SysUserPageDto extends PageQuery {

    @Schema(description = "登录账号（模糊查询）")
    private String username;

    @Schema(description = "用户姓名（模糊查询）")
    private String realName;

    @Schema(description = "所属部门ID")
    private Long deptId;

    @Schema(description = "状态：1启用，0禁用")
    private Integer status;

    @Schema(description = "角色ID")
    private Long roleId;
}