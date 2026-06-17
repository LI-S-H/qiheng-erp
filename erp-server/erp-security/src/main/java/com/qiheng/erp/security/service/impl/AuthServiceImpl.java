package com.qiheng.erp.security.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.util.PasswordUtil;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginRequest;
import com.qiheng.erp.security.domain.dto.LoginResponse;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.AuthService;
import com.qiheng.erp.security.service.LoginUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 认证服务：登录、退出、获取当前用户
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {
    @Autowired
    private LoginUserService loginUserService;
    @Autowired
    private PasswordUtil passwordUtil;

    /**
     * 登录
     * <p>
     * 流程：查用户 → 校验密码 → Sa-Token 登录 → 存 Session → 返回 token
     */
    public LoginResponse login(LoginRequest request) {
        // 1. 查询用户
        LoginUser loginUser = loginUserService.findByUsername(request.getUsername());
        if (loginUser == null) {
            throw new BizException(ErrorCode.USER_PASSWORD_ERROR);
        }

        // 2. 校验密码
        if (!passwordUtil.matches(request.getPassword(), loginUser.getPasswordHash())) {
            throw new BizException(ErrorCode.USER_PASSWORD_ERROR);
        }

        // 3. Sa-Token 登录
        StpUtil.login(loginUser.getUserId());

        // 4. 存入 Session
        UserContext.setCurrentUser(loginUser);

        // 5. 返回响应
        LoginResponse response = new LoginResponse();
        // 5.1. 设置 token
        response.setToken(StpUtil.getTokenValue());
        // 5.2. 设置 tokenName
        response.setTokenName(StpUtil.getTokenName());
        // 5.3. 设置用户信息
        response.setUser(loginUser);
        return response;
    }

    /**
     * 退出登录
     */
    public void logout() {
        StpUtil.logout();
    }

    /**
     * 获取当前登录用户信息
     */
    public LoginResponse me() {
        LoginUser user = UserContext.getCurrentUser();
        if (user == null) {
            throw new BizException(ErrorCode.USER_PASSWORD_ERROR);
        }
        LoginResponse response = new LoginResponse();
        // 1. 设置 token
        response.setToken(StpUtil.getTokenValue());
        // 2. 设置 tokenName
        response.setTokenName(StpUtil.getTokenName());
        // 3. 设置用户信息
        response.setUser(user);
        return response;
    }
}
