package com.qiheng.erp.security.service;

import com.qiheng.erp.security.domain.dto.LoginUser;

/**
 * 查询登录用户信息接口
 * <p>
 * erp-security 不直接访问用户表，由 erp-system 实现此接口提供数据。
 */
public interface LoginUserService {

    /**
     * 根据登录账号查询用户信息
     *
     * @param username 登录账号
     * @return 登录用户信息，包含部门名、角色编码和权限码；账号不存在或已删除返回 null
     */
    LoginUser findByUsername(String username);

    /**
     * 根据用户ID查询登录用户信息
     *
     * @param userId 用户ID
     * @return 登录用户信息
     */
    LoginUser findByUserId(Long userId);
}