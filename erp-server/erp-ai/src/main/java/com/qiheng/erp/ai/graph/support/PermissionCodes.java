package com.qiheng.erp.ai.graph.support;

import com.qiheng.erp.security.domain.dto.LoginUser;

import java.util.List;

/**
 * 权限码规范化。
 *
 * <p>权限码来自会话里的登录用户快照——与 ERP 现有权限校验
 * （{@code StpInterfaceImpl}）读的是同一份数据，所以 AI 看到的可见范围与用户
 * 实际能操作的模块天然一致，不会出现「AI 能查但用户点不了」或反之。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
public final class PermissionCodes {

    private PermissionCodes() {
    }

    /**
     * 从登录用户取出权限码。
     *
     * <p>超管的权限码是 {@code ["*"]}；其余是所有角色权限码的并集。</p>
     *
     * @param user 登录用户
     * @return 权限码列表；该用户确实没有任何权限码时返回空列表
     */
    public static List<String> of(LoginUser user) {
        if (user == null || user.getPermissionCodes() == null) {
            return List.of();
        }
        return List.copyOf(user.getPermissionCodes());
    }
}
