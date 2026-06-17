package com.qiheng.erp.security.controller;

import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.security.domain.dto.LoginRequest;
import com.qiheng.erp.security.domain.dto.LoginResponse;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.AuthService;
import com.qiheng.erp.security.service.impl.AuthServiceImpl;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证接口：登录、退出、获取当前用户
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor

public class AuthController {

    private final AuthService authService;

    /**
     * 登录
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody @Valid LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    /**
     * 退出登录
     */
    @PostMapping("/logout")
    public Result<Void> logout() {
        authService.logout();
        return Result.ok();
    }

    /**
     * 获取当前登录用户信息
     */
    @GetMapping("/me")
    public Result<LoginUser> me() {
        return Result.ok(authService.me());
    }
}
