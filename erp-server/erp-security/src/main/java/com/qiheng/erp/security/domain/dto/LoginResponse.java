package com.qiheng.erp.security.domain.dto;

import lombok.Data;

/**
 * 登录响应
 */
@Data
public class LoginResponse {

    /** Sa-Token 生成的 token 值 */
    private String token;

    /** 请求头名称，前端后续请求需以此作为 header key */
    private String tokenName;

    /** 当前登录用户信息 */
    private LoginUser user;
}
