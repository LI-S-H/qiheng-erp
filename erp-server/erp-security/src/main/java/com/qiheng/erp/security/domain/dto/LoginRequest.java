package com.qiheng.erp.security.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "登录请求")
public class LoginRequest {

    @Schema(description = "登录账号")
    @NotBlank(message = "请输入登录账号")
    private String username;

    @Schema(description = "密码")
    @NotBlank(message = "请输入密码")
    private String password;
}