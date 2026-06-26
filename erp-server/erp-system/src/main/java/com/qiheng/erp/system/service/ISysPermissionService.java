package com.qiheng.erp.system.service;

import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.system.domain.dto.SysPermissionPageDto;
import com.qiheng.erp.system.domain.entity.SysPermission;
import com.qiheng.erp.system.domain.vo.SysPermissionVo;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.validation.Valid;

import java.util.List;

/**
 * <p>
 * 权限码目录表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
public interface ISysPermissionService extends IService<SysPermission> {

    /**
     * 分页查询权限码列表
     * @param dto 分页查询请求
     * @return 权限码列表
     */
    PageResult<SysPermissionVo> page(SysPermissionPageDto dto);

    /**
     * 根据ID查询权限码详情
     * @param permissionId 权限码ID
     * @return 权限码详情
     */
    SysPermissionVo getDetailById(@Valid Long permissionId);

    /**
     * 新增权限码
     * @param permission 权限码信息
     * @return 新增结果
     */
    SysPermissionVo insert(@Valid SysPermission permission);

    /**
     * 更新权限码
     * @param permission 权限码信息
     * @return 更新结果
     */
    SysPermissionVo updatePermission(@Valid SysPermission permission);

    /**
     * 批量删除权限码
     * @param permissionIds 权限码ID映射，键为角色ID，值为权限码ID列表
     */
    void batchDeletePermissions(List<String> permissionIds);

    /**
     * 更新权限码状态
     * @param permissionId 权限码ID
     * @param statusValue 状态值，0-停用，1-启用
     */
    void updatePermissionStatus(String permissionId, Integer statusValue);

    /**
     * 批量更新权限码状态
     * @param permissionIds 权限码ID列表，至少选择1条
     * @param status 状态值，0-停用，1-启用
     */
    void batchUpdateStatus(List<String> permissionIds, Integer status);
}