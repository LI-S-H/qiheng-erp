package com.qiheng.erp.system.service;

import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.system.domain.dto.SysRolePageDto;
import com.qiheng.erp.system.domain.entity.SysRole;
import com.qiheng.erp.system.domain.vo.RoleOptionVo;
import com.qiheng.erp.system.domain.vo.SysRoleVo;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.validation.Valid;

import java.util.List;

/**
 * <p>
 * 角色表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
public interface ISysRoleService extends IService<SysRole> {

    /**
     * 分页查询角色列表
     * @param dto 分页查询请求
     * @return 角色列表
     */
    PageResult<SysRoleVo> page(SysRolePageDto dto);

    /**
     * 根据角色ID查询角色详情
     * @param roleId 角色ID
     * @return 角色详情
     */
    SysRoleVo getDetailById(Long roleId);

    /**
     * 添加角色
     * @param sysRole 角色信息
     */
    SysRoleVo add(@Valid SysRole sysRole);

    /**
     * 批量修改角色状态
     * @param roleIds 角色ID列表
     * @param status 目标状态：1启用，0禁用
     */
    void batchUpdateStatus(List<String> roleIds, Integer status);

    /**
     * 修改角色状态
     * @param roleId 角色ID
     * @param status 目标状态：1启用，0禁用
     */
    void updateStatus(Long roleId, Integer status);

    /**
     *  删除角色
     * @param list 角色ID列表
     */
    void deleteByIds(List<String> list);

    /**
     * 删除角色
     * @param roleId 角色ID
     */
    void deleteById(Long roleId);

    /**
     * 更新角色
     * @param sysRole 角色信息
     * @return 更新后的角色信息
     */
    SysRoleVo updateRole(Long roleId, SysRole sysRole);

    /**
     * 修改角色权限码
     * @param roleId 角色ID
     * @param list 权限码列表
     */
    void updatePermissionCodes(Long roleId, List<String> list);

    /**
     * 查询角色权限码选项分组列表
     * @return 角色权限码选项分组列表
     */
    List<RoleOptionVo> getPermissionCodeOptions();
}