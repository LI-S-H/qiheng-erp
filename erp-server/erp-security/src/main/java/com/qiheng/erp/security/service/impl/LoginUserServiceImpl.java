package com.qiheng.erp.security.service.impl;

import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import com.qiheng.erp.system.domain.entity.SysDept;
import com.qiheng.erp.system.domain.entity.SysRole;
import com.qiheng.erp.system.domain.entity.SysUser;
import com.qiheng.erp.system.domain.entity.SysUserRole;
import com.qiheng.erp.system.mapper.SysRoleMapper;
import com.qiheng.erp.system.mapper.SysUserMapper;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

@Service
public class LoginUserServiceImpl implements LoginUserService {
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysRoleMapper sysRoleMapper;

    @Override
    public LoginUser findByUsername(String username) {
        // 1. 根据登录账号查询用户信息，LEFT JOIN sys_dept 一次查出部门名称
        LoginUser loginUser = sysUserMapper.selectJoinOne(LoginUser.class,
                new MPJLambdaWrapper<SysUser>()
                        .selectAs(SysUser::getId, LoginUser::getUserId)
                        .select(SysUser::getUsername)
                        .select(SysUser::getRealName)
                        .select(SysUser::getDeptId)
                        .selectAs(SysDept::getDeptName, LoginUser::getDeptName)
                        .selectAs(SysUser::getIsAdmin, LoginUser::getIsAdmin)
                        .select(SysUser::getPasswordHash)
                        .selectAs(SysUser::getLastLoginAt, LoginUser::getLastLoginAt)
                        .leftJoin(SysDept.class, SysDept::getId, SysUser::getDeptId)
                        .eq(SysUser::getUsername, username)
        );
        if (loginUser == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        // 2. 根据用户id联查角色信息和角色权限码
        List<SysRole> sysRoles = sysRoleMapper.selectJoinList(SysRole.class,
                new MPJLambdaWrapper<SysRole>()
                        .select(SysRole::getRoleCode)
                        .select(SysRole::getPermissionCodes)
                        .innerJoin(SysUserRole.class, SysUserRole::getRoleId, SysRole::getId)
                        .eq(SysUserRole::getUserId, loginUser.getUserId())
        );

        // 3. 封装角色编码列表
        return getLoginUser(loginUser, sysRoles);
    }

    @Override
    public LoginUser findByUserId(Long userId) {
        // 1. 根据用户id联查用户信息和角色信息
        LoginUser loginUser = sysUserMapper.selectJoinOne(LoginUser.class,
                new MPJLambdaWrapper<SysUser>()
                        .selectAs(SysUser::getId, LoginUser::getUserId)
                        .select(SysUser::getUsername)
                        .select(SysUser::getRealName)
                        .select(SysUser::getDeptId)
                        .selectAs(SysDept::getDeptName, LoginUser::getDeptName)
                        .selectAs(SysUser::getIsAdmin, LoginUser::getIsAdmin)
                        .select(SysUser::getPasswordHash)
                        .selectAs(SysUser::getLastLoginAt, LoginUser::getLastLoginAt)
                        .leftJoin(SysDept.class, SysDept::getId, SysUser::getDeptId)
                        .eq(SysUser::getId, userId)
        );
        if (loginUser == null) {
            return null;
        }
        // 2. 根据用户id联查角色信息和角色权限码
        List<SysRole> sysRoles = sysRoleMapper.selectJoinList(SysRole.class,
                new MPJLambdaWrapper<SysRole>()
                        .select(SysRole::getRoleCode)
                        .select(SysRole::getPermissionCodes)
                        .innerJoin(SysUserRole.class, SysUserRole::getRoleId, SysRole::getId)
                        .eq(SysUserRole::getUserId, userId)
                        .eq(SysRole::getStatus,1)
        );
        // 3. 封装角色编码列表
        return getLoginUser(loginUser, sysRoles);
    }

    @NotNull
    private LoginUser getLoginUser(LoginUser loginUser, List<SysRole> sysRoles) {
        loginUser.setRoleCodes(sysRoles.stream().map(SysRole::getRoleCode).toList());
        if (loginUser.getIsAdmin()) {
            loginUser.setPermissionCodes(List.of("*"));
        } else {
            loginUser.setPermissionCodes(sysRoles.stream()
                    .map(SysRole::getPermissionCodes)
                    .filter(permissionCodes -> permissionCodes != null && !permissionCodes.isEmpty())
                    .flatMap(Collection::stream)
                    .toList());
        }
        return loginUser;
    }
}