package com.qiheng.erp.security.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 当前登录用户信息，登录成功后存入 Sa-Token Session
 */
@Data
public class LoginUser implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 用户ID */
    private Long userId;

    /** 登录账号 */
    private String username;

    /** 用户姓名 */
    private String realName;

    /** 所属部门ID */
    private Long deptId;

    /** 所属部门名称 */
    private String deptName;

    /** 是否超级管理员 */
    private Boolean isAdmin;

    /** 角色编码列表 */
    private List<String> roleCodes;

    /** 密码哈希，仅登录校验使用，不返回给前端 */
    @JsonIgnore
    private String passwordHash;

    /** 权限码列表，超级管理员为 ["*"] */
    private List<String> permissionCodes;

    /** 最近登录时间 */
    private LocalDateTime lastLoginAt;
}