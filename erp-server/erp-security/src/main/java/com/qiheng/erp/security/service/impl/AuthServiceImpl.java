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
import com.qiheng.erp.system.domain.entity.SysUser;
import com.qiheng.erp.system.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

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
    @Autowired
    private SysUserMapper sysUserMapper;

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
        // 4. 更新最近登录时间
        LocalDateTime now = LocalDateTime.now();
        SysUser update = new SysUser();
        update.setId(loginUser.getUserId());
        update.setLastLoginAt(now);
        sysUserMapper.updateById(update);
        loginUser.setLastLoginAt(now);
        // 5. 存入 Session
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
    public LoginUser me() {
        LoginUser user = UserContext.getCurrentUser();
        if (user == null) {
            throw new BizException(ErrorCode.USER_PASSWORD_ERROR);
        }
        return user;
    }
}