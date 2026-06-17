package com.qiheng.erp.security.service;

import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.security.domain.dto.LoginRequest;
import com.qiheng.erp.security.domain.dto.LoginResponse;
import com.qiheng.erp.security.domain.dto.LoginUser;

/**
 * 认证服务：登录、退出、获取当前用户
 */
public interface AuthService {

    /**
     * 登录
     * @param request
     * @return
     */
    LoginResponse login(LoginRequest request);

    /**
     * 退出登录
     * @return
     * @throws BizException
     *     当用户未登录时抛出
     */
    void logout();

    /**
     * 获取当前登录用户信息
     * @return 当前登录用户信息
     * @throws BizException
     *     当用户未登录时抛出
     */
    LoginUser me();
}
