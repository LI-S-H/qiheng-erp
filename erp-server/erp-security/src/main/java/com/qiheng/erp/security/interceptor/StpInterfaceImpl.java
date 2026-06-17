package com.qiheng.erp.security.interceptor;

import cn.dev33.satoken.stp.StpInterface;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * Sa-Token 权限接口实现，提供当前用户的角色编码和权限码列表
 */
@Component
public class StpInterfaceImpl implements StpInterface {

    /**
     * 获取当前用户的权限码列表
     * @param loginId
     * @param loginType
     * @return
     */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        LoginUser user = UserContext.getCurrentUser();
        if (user == null) {
            return Collections.emptyList();
        }
        return user.getPermissionCodes();
    }

    /**
     * 获取当前用户的角色编码列表
     * @param loginId
     * @param loginType
     * @return
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        LoginUser user = UserContext.getCurrentUser();
        if (user == null) {
            return Collections.emptyList();
        }
        return user.getRoleCodes();
    }
}
