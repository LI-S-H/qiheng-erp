package com.qiheng.erp.security.context;

import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.security.domain.dto.LoginUser;

/**
 * 当前登录用户上下文，在任何地方获取当前用户信息
 */
public class UserContext {

    private static final String SESSION_KEY = "loginUser";

    private UserContext() {
    }

    /**
     * 获取当前登录用户，未登录返回 null
     */
    public static LoginUser getCurrentUser() {
        if (!StpUtil.isLogin()) {
            return null;
        }
        return (LoginUser) StpUtil.getSession().get(SESSION_KEY);
    }

    /**
     * 获取当前登录用户，未登录直接抛异常
     * 用于需要当前用户的业务场景，避免 null 导致数据脏
     */
    public static LoginUser requireCurrentUser() {
        LoginUser user = getCurrentUser();
        if (user == null) {
            throw new cn.dev33.satoken.exception.NotLoginException("未登录或登录已过期", null, "not-login");
        }
        return user;
    }

    /**
     * 将登录用户信息存入 Session
     */
    public static void setCurrentUser(LoginUser loginUser) {
        StpUtil.getSession().set(SESSION_KEY, loginUser);
    }

    /**
     * 获取当前用户ID，未登录返回 null
     */
    public static Long getUserId() {
        LoginUser user = getCurrentUser();
        return user != null ? user.getUserId() : null;
    }

    /**
     * 获取当前用户登录账号，未登录返回 null
     */
    public static String getUsername() {
        LoginUser user = getCurrentUser();
        return user != null ? user.getUsername() : null;
    }

    /**
     * 判断当前用户是否超级管理员，未登录返回 false
     */
    public static boolean isAdmin() {
        LoginUser user = getCurrentUser();
        return user != null && Boolean.TRUE.equals(user.getIsAdmin());
    }
}
