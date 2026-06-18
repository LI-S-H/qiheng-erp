package com.qiheng.erp.security.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "当前登录用户信息")
public class LoginUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "登录账号")
    private String username;

    @Schema(description = "用户姓名")
    private String realName;

    @Schema(description = "所属部门ID")
    private Long deptId;

    @Schema(description = "所属部门名称")
    private String deptName;

    @Schema(description = "是否超级管理员")
    private Boolean isAdmin;

    @Schema(description = "角色编码列表")
    private List<String> roleCodes;

    @JsonIgnore
    @Schema(description = "密码哈希，仅登录校验使用，不返回给前端")
    private String passwordHash;

    @Schema(description = "权限码列表，超级管理员为 [\"*\"]")
    private List<String> permissionCodes;

    @Schema(description = "最近登录时间")
    private LocalDateTime lastLoginAt;
}