package com.qiheng.erp.system.service;

import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.system.domain.dto.SysUserPageDto;
import com.qiheng.erp.system.domain.dto.SysUserStatusUpdateDto;
import com.qiheng.erp.system.domain.dto.UserPasswordUpdateDto;
import com.qiheng.erp.system.domain.entity.SysUser;
import com.qiheng.erp.system.domain.vo.SysUserVo;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * <p>
 * 用户表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
public interface ISysUserService extends IService<SysUser> {

    /**
     * 用户分页查询
     *
     * @param dto 查询条件（含分页参数）
     * @return 分页结果
     */
    PageResult<SysUserVo> page(SysUserPageDto dto);

    /**
     * 用户详情查询
     * @param userId 用户ID
     * @return 用户详情
     */
    SysUserVo getDetailById(@NotNull Long userId);

    /**
     * 用户新增
     * @param sysUser 用户实体
     * @return 新增结果
     */
    SysUserVo saveUser(@NotNull SysUser sysUser);

    /**
     * 批量更新用户状态
     * @param dto 更新状态DTO
     */
    void updateStatus(@Valid SysUserStatusUpdateDto dto);

    /**
     * 更新用户状态
     * @param userId 用户ID
     * @param status 状态值
     */
    void updateStatusById(Long userId, Integer status);

    /**
     * 批量更新用户密码
     * @param dto 更新密码DTO
     */
    void updatePasswordByIds(@Valid UserPasswordUpdateDto dto);

    /**
     * 更新用户密码
     * @param userId 用户ID
     * @param password 登录密码
     */
    void updatePasswordById(Long userId, String password);

    /**
     * 更新用户
     * @param userId 用户ID
     * @param sysUser 用户实体
     * @return 更新结果VO
     */
    SysUserVo updateUser(Long userId, SysUser sysUser);

    /**
     * 更新用户角色
     * @param userId 用户ID
     * @param roleIds 角色ID列表
     */
    void updateRoles(Long userId, List<String> roleIds);

    /**
     * 批量删除用户
     * @param ids 用户ID列表
     */
    void deleteBatch(List<String> ids);
}