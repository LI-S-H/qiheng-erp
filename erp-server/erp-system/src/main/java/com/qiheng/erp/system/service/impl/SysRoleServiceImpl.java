package com.qiheng.erp.system.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.system.manager.SessionManager;
import com.qiheng.erp.system.domain.dto.SysRolePageDto;
import com.qiheng.erp.system.domain.entity.SysRole;
import com.qiheng.erp.system.domain.entity.SysUserRole;
import com.qiheng.erp.system.domain.vo.SysRoleVo;
import com.qiheng.erp.system.mapper.SysRoleMapper;
import com.qiheng.erp.system.mapper.SysUserRoleMapper;
import com.qiheng.erp.system.service.ISysRoleService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * <p>
 * 角色表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
@Service
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements ISysRoleService {

    @Autowired
    private SysRoleMapper sysRoleMapper;
    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;
    @Autowired
    private SessionManager sessionManager;

    /**
     * 分页查询角色列表
     *
     * @param dto 分页查询请求
     * @return 角色列表
     */
    @Override
    public PageResult<SysRoleVo> page(SysRolePageDto dto) {
        MPJLambdaWrapper<SysRole> wrapper = new MPJLambdaWrapper<SysRole>()
                .selectAs(SysRole::getId, SysRoleVo::getRoleId)
                .select(SysRole::getRoleCode)
                .select(SysRole::getRoleName)
                .select(SysRole::getPermissionCodes)
                .select(SysRole::getStatus)
                .select(SysRole::getCreateTime)
                .select(SysRole::getUpdateTime)
                .select(SysRole::getRemark)
                .selectCount(SysUserRole::getId, SysRoleVo::getUserCount)
                .leftJoin(SysUserRole.class, SysUserRole::getRoleId, SysRole::getId)
                .like(StrUtil.isNotBlank(dto.getRoleCode()), SysRole::getRoleCode, dto.getRoleCode())
                .like(StrUtil.isNotBlank(dto.getRoleName()), SysRole::getRoleName, dto.getRoleName())
                .eq(dto.getStatus() != null, SysRole::getStatus, dto.getStatus())
                .groupBy(SysRole::getId)
                .orderByDesc(SysRole::getCreateTime);
        Page<SysRoleVo> result = sysRoleMapper.selectJoinPage(dto.toPage(), SysRoleVo.class, wrapper);
        return PageResult.of(result.getRecords(), (int) result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /**
     * 根据角色ID查询角色详情
     *
     * @param roleId 角色ID
     * @return 角色详情
     */
    @Override
    public SysRoleVo getDetailById(Long roleId) {
        MPJLambdaWrapper<SysRole> wrapper = new MPJLambdaWrapper<SysRole>()
                .selectAs(SysRole::getId, SysRoleVo::getRoleId)
                .select(SysRole::getRoleCode)
                .select(SysRole::getRoleName)
                .select(SysRole::getPermissionCodes)
                .select(SysRole::getStatus)
                .select(SysRole::getCreateTime)
                .select(SysRole::getUpdateTime)
                .select(SysRole::getRemark)
                .selectCount(SysUserRole::getId, SysRoleVo::getUserCount)
                .leftJoin(SysUserRole.class, SysUserRole::getRoleId, SysRole::getId)
                .eq(SysRole::getId, roleId)
                .groupBy(SysRole::getId);
        return sysRoleMapper.selectJoinOne(SysRoleVo.class, wrapper);
    }

    /**
     * 新增角色
     *
     * @param sysRole 角色信息
     */
    @Override
    public SysRoleVo add(SysRole sysRole) {
        sysRoleMapper.insert(sysRole);
        return getDetailById(sysRole.getId());
    }

    /**
     * 批量修改角色状态
     *
     * @param roleIds 角色ID列表
     * @param status  目标状态：1启用，0禁用
     */
    @Override
    @DistributedLock(key = "'sys:role:lock:global'")
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateStatus(List<String> roleIds, Integer status) {
        List<Long> ids = roleIds.stream().map(Long::valueOf).toList();
        LambdaUpdateWrapper<SysRole> wrapper = new LambdaUpdateWrapper<SysRole>()
                .set(SysRole::getStatus, status)
                .in(SysRole::getId, ids);
        sysRoleMapper.update(wrapper);
        if (status == 0) {
            //查出受影响的用户ID，用于后续刷新Session
            List<Long> userIds = sysUserRoleMapper.selectList(
                    new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getRoleId, ids))
                    .stream().map(SysUserRole::getUserId).distinct().toList();
            //删除角色与用户的关联关系
            sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getRoleId, ids));
            //刷新受影响用户的Session，使其权限快照实时生效
            sessionManager.refreshUserSession(userIds);
        }
    }

    /**
     * 修改角色状态
     * @param roleId 角色ID
     * @param status 目标状态：1启用，0禁用
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long roleId, Integer status) {
        LambdaUpdateWrapper<SysRole> wrapper = new LambdaUpdateWrapper<SysRole>()
                .set(SysRole::getStatus, status)
                .eq(SysRole::getId, roleId);
        sysRoleMapper.update(wrapper);
        if (status == 0) {
            //查出受影响的用户ID，用于后续刷新Session
            List<Long> userIds = sysUserRoleMapper.selectList(
                    new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, roleId))
                    .stream().map(SysUserRole::getUserId).distinct().toList();
            //删除角色与用户的关联关系
            sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, roleId));
            //刷新受影响用户的Session，使其权限快照实时生效
            sessionManager.refreshUserSession(userIds);
        }
    }

    /**
     * 批量删除角色
     * @param list 角色ID列表
     */
    @Override
    @DistributedLock(key = "'sys:role:lock:global'")
    @Transactional(rollbackFor = Exception.class)
    public void deleteByIds(List<String> list) {
        List<Long> ids = list.stream().map(Long::valueOf).toList();
        //先查出受影响的用户ID，删除后需要刷新这些用户的Session
        List<Long> userIds = sysUserRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getRoleId, ids))
                .stream().map(SysUserRole::getUserId).distinct().toList();
        //删除角色
        sysRoleMapper.deleteByIds(list);
        //删除角色与用户的关联关系
        sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getRoleId, ids));
        //刷新受影响用户的Session，使其权限快照实时生效
        sessionManager.refreshUserSession(userIds);
    }

    /**
     * 删除角色
     * @param roleId 角色ID
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteById(Long roleId) {
        //查出受影响的用户ID，用于后续刷新Session
        List<Long> userIds = sysUserRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, roleId))
                .stream().map(SysUserRole::getUserId).distinct().toList();
        //删除角色
        sysRoleMapper.deleteById(roleId);
        //删除角色与用户的关联关系
        sysUserRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, roleId));
        //刷新受影响用户的Session，使其权限快照实时生效
        sessionManager.refreshUserSession(userIds);
    }

    /**
     * 更新角色
     * @param sysRole 角色信息
     * @return 更新后的角色信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @DistributedLock(key = "'sys:role:lock:global'")
    public SysRoleVo updateRole(Long roleId, SysRole sysRole) {
        //查出受影响的用户ID，用于后续刷新Session
        List<Long> userIds = sysUserRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, sysRole.getId()))
                .stream().map(SysUserRole::getUserId).distinct().toList();
        //更新角色信息
        sysRoleMapper.updateById(sysRole);
        //刷新受影响用户的Session，使其权限快照实时生效
        sessionManager.refreshUserSession(userIds);
        return getDetailById(roleId);
    }

    /**
     * 修改角色权限码
     * @param roleId 角色ID
     * @param list 目标权限码列表
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @DistributedLock(key = "'sys:role:lock:global'")
    public void updatePermissionCodes(Long roleId, List<String> list) {
        //查出受影响的用户ID，用于后续刷新Session
        List<Long> userIds = sysUserRoleMapper.selectList(
                new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, roleId))
                .stream().map(SysUserRole::getUserId).distinct().toList();
        //更新角色权限码
        sysRoleMapper.updateById(new SysRole()
                .setId(roleId)
                .setPermissionCodes(list));
        //刷新受影响用户的Session，使其权限快照实时生效
        sessionManager.refreshUserSession(userIds);
    }

}