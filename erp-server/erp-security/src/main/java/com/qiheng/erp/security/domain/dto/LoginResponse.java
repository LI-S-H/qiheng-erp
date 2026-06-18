package com.qiheng.erp.security.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "登录响应")
public class LoginResponse {

    @Schema(description = "Sa-Token 生成的 token 值")
    private String token;

    @Schema(description = "请求头名称，前端后续请求需以此作为 header key")
    private String tokenName;

    @Schema(description = "当前登录用户信息")
    private LoginUser user;
}