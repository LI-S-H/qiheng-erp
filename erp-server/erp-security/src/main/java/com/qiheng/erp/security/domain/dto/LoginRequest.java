package com.qiheng.erp.security.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 登录请求
 */
@Data
public class LoginRequest {

    /** 登录账号 */
    @NotBlank(message = "请输入登录账号")
    private String username;

    /** 密码 */
    @NotBlank(message = "请输入密码")
    private String password;
}
