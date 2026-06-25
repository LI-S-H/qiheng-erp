package com.qiheng.erp.security.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import com.qiheng.erp.system.manager.SessionManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Session 管理器实现类
 * <p>
 * 依赖 erp-security 中的 LoginUserService 和 UserContext 来完成 Session 刷新，
 * 通过实现 erp-system 中定义的 SessionManager 接口，避免模块循环依赖。
 * </p>
 */
@Component
public class SessionManagerImpl implements SessionManager {

    @Autowired
    private LoginUserService loginUserService;

    /**
     * 刷新指定用户的 Session
     * @param userId 用户ID
     */
    @Override
    public void refreshUserSession(Long userId) {
        // 如果用户未登录，直接返回
        if (!StpUtil.isLogin(userId)) {
            return;
        }
        LoginUser loginUser = loginUserService.findByUserId(userId);
        // 如果用户不存在或已删除，直接下线
        if (loginUser == null) {
            StpUtil.logout(userId);
            return;
        }
        // 刷新用户上下文
        UserContext.setCurrentUser(loginUser);
    }

    /**
     * 刷新多个用户的 Session
     * @param userIds 用户ID列表
     */
    @Override
    public void refreshUserSession(List<Long> userIds) {
        userIds.forEach(this::refreshUserSession);
    }

    /**
     * 强制单个用户下线
     * @param userId 用户ID
     */
    @Override
    public void kickOffline(Long userId) {
        StpUtil.logout(userId);
    }

    /**
     * 批量强制用户下线
     * @param userIds 用户ID列表
     */
    @Override
    public void kickOffline(List<Long> userIds) {
        userIds.forEach(StpUtil::logout);
    }
}