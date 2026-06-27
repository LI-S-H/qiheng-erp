package com.qiheng.erp.system.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.util.RedisUtil;
import com.qiheng.erp.system.domain.dto.SysPermissionPageDto;
import com.qiheng.erp.system.domain.entity.SysPermission;
import com.qiheng.erp.system.domain.entity.SysRole;
import com.qiheng.erp.system.domain.entity.SysUserRole;
import com.qiheng.erp.system.domain.vo.PermissionOptionGroupVo;
import com.qiheng.erp.system.domain.vo.SysPermissionVo;
import com.qiheng.erp.system.manager.SessionManager;
import com.qiheng.erp.system.mapper.SysPermissionMapper;
import com.qiheng.erp.system.mapper.SysRoleMapper;
import com.qiheng.erp.system.mapper.SysUserRoleMapper;
import com.qiheng.erp.system.service.ISysPermissionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <p>
 * 权限码目录表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
@Service
public class SysPermissionServiceImpl extends ServiceImpl<SysPermissionMapper, SysPermission> implements ISysPermissionService {

    private static final String CACHE_KEY = "system:options:permissions";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    @Autowired
    private SysPermissionMapper sysPermissionMapper;
    @Autowired
    private SysRoleMapper sysRoleMapper;
    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;
    @Autowired
    private SessionManager sessionManager;
    @Autowired
    private RedisUtil redisUtil;

    /**
     * 分页查询权限码列表
     * @param dto 分页查询请求
     * @return 权限码列表分页查询响应
     */
    @Override
    public PageResult<SysPermissionVo> page(SysPermissionPageDto dto) {
        // 构建查询条件
        LambdaQueryWrapper<SysPermission> wrapper = new LambdaQueryWrapper<SysPermission>()
                .like(StrUtil.isNotBlank(dto.getPermissionCode()), SysPermission::getPermissionCode, dto.getPermissionCode())
                .like(StrUtil.isNotBlank(dto.getPermissionName()), SysPermission::getPermissionName, dto.getPermissionName())
                .eq(StrUtil.isNotBlank(dto.getModuleCode()), SysPermission::getModuleCode, dto.getModuleCode())
                .eq(StrUtil.isNotBlank(dto.getActionType()), SysPermission::getActionType, dto.getActionType())
                .eq(dto.getStatus() != null, SysPermission::getStatus, dto.getStatus())
                .orderByAsc(SysPermission::getSortOrder)
                .orderByDesc(SysPermission::getCreateTime);

        Page<SysPermission> page = sysPermissionMapper.selectPage(dto.toPage(), wrapper);

        // 查询所有角色，统计每个权限码的使用次数
        List<SysRole> allRoles = sysRoleMapper.selectList(
                new LambdaQueryWrapper<SysRole>().eq(SysRole::getStatus, 1).eq(SysRole::getDeleted, 0)
        );

        // 统计权限码使用次数
        int wildcardCount = 0;
        Map<String, Integer> roleCountMap = new HashMap<>();
        for (SysRole role : allRoles) {
            if (role.getPermissionCodes() == null) {
                continue;
            }
            if (role.getPermissionCodes().contains("*")) {
                // 包含通配符，直接累加角色数量
                wildcardCount++;
                continue;
            }
            for (String code : role.getPermissionCodes()) {
                // 累加角色数量
                roleCountMap.merge(code, 1, Integer::sum);
            }
        }

        int finalWildcardCount = wildcardCount;
        List<SysPermissionVo> voList = page.getRecords().stream().map(p -> {
            SysPermissionVo vo = getSysPermissionVo(p);
            // 引用该权限码的角色数量+通配符角色数量
            vo.setRoleCount(roleCountMap.getOrDefault(p.getPermissionCode(), 0) + finalWildcardCount);
            return vo;
        }).toList();

        return PageResult.of(voList, (int) page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    /**
     * 根据ID查询权限码详情
     * @param permissionId 权限码ID
     * @return 权限码详情
     */
    @Override
    public SysPermissionVo getDetailById(Long permissionId) {
        SysPermission permission = sysPermissionMapper.selectById(permissionId);
        if (permission == null) {
            return null;
        }
        SysPermissionVo vo = getSysPermissionVo(permission);
        return fillRoleCount(vo);
    }


    /**
     * 新增权限码
     * @param permission 权限码信息
     * @return 新增结果
     */
    @Override
    public SysPermissionVo insert(SysPermission permission) {
        // 新增权限码
        sysPermissionMapper.insert(permission);
        // 查询新增权限码详情
        SysPermission sysPermission = sysPermissionMapper.selectById(permission.getId());
        // 转换为VO
        SysPermissionVo vo = getSysPermissionVo(sysPermission);
        // 新增权限码，引用该权限码的角色数量为0
        vo.setRoleCount(0);
        redisUtil.delete(CACHE_KEY);
        return vo;
    }

    /**
     * 更新权限码
     * @param permission 权限码信息
     * @return 更新结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    @DistributedLock(key = "'sys:permission:lock:global'")
    public SysPermissionVo updatePermission(SysPermission permission) {
        //先查询更新前的权限码信息
        SysPermission oldPermission = sysPermissionMapper.selectById(permission.getId());
        if (oldPermission == null) {
            throw new BizException(ErrorCode.PERMISSION_NOT_FOUND);
        }

        // 更新权限码
        sysPermissionMapper.updateById(permission);
        // 查询更新后的权限码详情
        SysPermission sysPermission = sysPermissionMapper.selectById(permission.getId());
        // 停用权限码时，从角色的 permission_codes 中删除该权限码并刷新受影响用户
        if (oldPermission.getStatus() == 1 && permission.getStatus() == 0) {
            // 删除所有包含该权限码的启用角色的权限码,并刷新受影响用户会话
            deleteRolePermission(List.of(oldPermission));
        }
        // 转换为VO
        redisUtil.delete(CACHE_KEY);
        SysPermissionVo vo = getSysPermissionVo(sysPermission);
        // 查询所有角色，统计该权限码的使用次数，并填充引用该权限码的角色数量
        return fillRoleCount(vo);
    }

    /**
     * 填充引用该权限码的角色数量
     * @param vo 权限码VO
     * @return 填充后的权限码VO
     */
    private SysPermissionVo fillRoleCount(SysPermissionVo vo) {
        List<SysRole> affectedRoles = sysRoleMapper.selectByPermissionCodes(List.of(vo.getPermissionCode(), "*"));
        vo.setRoleCount(affectedRoles.size());
        return vo;
    }

    /**
     * 转换为VO
     * @param permission 权限码实体
     * @return 权限码VO
     */
    private SysPermissionVo getSysPermissionVo(SysPermission permission) {
        SysPermissionVo vo = new SysPermissionVo();
        vo.setPermissionId(permission.getId());
        vo.setPermissionCode(permission.getPermissionCode());
        vo.setPermissionName(permission.getPermissionName());
        vo.setModuleCode(permission.getModuleCode());
        vo.setActionType(permission.getActionType());
        vo.setStatus(permission.getStatus());
        vo.setSortOrder(permission.getSortOrder());
        vo.setDescription(permission.getDescription());
        vo.setCreateTime(permission.getCreateTime());
        vo.setUpdateTime(permission.getUpdateTime());
        return vo;
    }


    /**
     * 批量删除权限码
     * @param permissionIds 权限码ID映射，键为角色ID，值为权限码ID列表
     */
    @Override
    @DistributedLock(key = "'sys:permission:lock:global'")
    public void batchDeletePermissions(List<String> permissionIds) {
        // 先查询是否存在该权限码
        List<SysPermission> permissions = sysPermissionMapper.selectByIds(permissionIds);
        if (permissions.isEmpty()||permissions.size() != permissionIds.size()) {
            throw new BizException(ErrorCode.PERMISSION_NOT_FOUND);
        }
        // 查询出所有引用该权限码的角色
        List<String> permCodes = permissions.stream().map(SysPermission::getPermissionCode).toList();
        List<SysRole> roles = sysRoleMapper.selectByPermissionCodes(permCodes);
        // 如果有角色引用该权限码，抛出异常
        if (!roles.isEmpty()) {
            throw new BizException(ErrorCode.PERMISSION_IN_USE);
        }
        // 删除权限码
        sysPermissionMapper.deleteByIds(permissionIds);
        redisUtil.delete(CACHE_KEY);
    }

    /**
     * 更新权限码状态
     * @param permissionId 权限码ID
     * @param statusValue 状态值，0-停用，1-启用
     */
    @Override
    @DistributedLock(key = "'sys:permission:lock:global'")
    @Transactional(rollbackFor = Exception.class)
    public void updatePermissionStatus(String permissionId, Integer statusValue) {
        SysPermission permission = sysPermissionMapper.selectById(permissionId);
        if (permission == null) {
            throw new BizException(ErrorCode.PERMISSION_NOT_FOUND);
        }
        if (permission.getStatus().equals(statusValue)) {
            return;
        }
        sysPermissionMapper.updateById(new SysPermission().setId(Long.valueOf(permissionId)).setStatus(statusValue));
        if (statusValue == 0) {
            // 停用权限码，删除角色权限码,并刷新受影响用户会话
            deleteRolePermission(List.of(permission));
        }
        redisUtil.delete(CACHE_KEY);
    }

    /**
     * 批量更新权限码状态
     * @param permissionIds 权限码ID列表，至少选择1条
     * @param status 状态值，0-停用，1-启用
     */
    @Override
    @DistributedLock(key = "'sys:permission:lock:global'")
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateStatus(List<String> permissionIds, Integer status) {
        // 先查询是否存在该权限码
        List<SysPermission> permissions = sysPermissionMapper.selectByIds(permissionIds);
        if (permissions.isEmpty()||permissions.size() != permissionIds.size()) {
            throw new BizException(ErrorCode.PERMISSION_NOT_FOUND);
        }
        // 更新权限码状态
        sysPermissionMapper.updateById(permissions.stream()
                .map(permission -> new SysPermission().setId(permission.getId()).setStatus(status))
                .toList());
        // 停用权限码，删除角色权限码,并刷新受影响用户会话
        if (status == 0) {
            deleteRolePermission(permissions);
        }
        redisUtil.delete(CACHE_KEY);
    }

    /**
     * 删除角色权限码并刷新受影响用户会话
     * @param permissions 权限码列表
     */
    private void deleteRolePermission(List<SysPermission> permissions) {
        // 得到所有的权限码
        List<String> permCodes = permissions.stream().map(SysPermission::getPermissionCode).toList();
        // 查询出所有引用该权限码的角色
        List<SysRole> roles = sysRoleMapper.selectByPermissionCodes(permCodes);
        if (!roles.isEmpty()) {
            for (SysRole role : roles) {
                if (role.getPermissionCodes() == null) {
                    continue;
                }
                // 过滤出未引用的权限码
                role.setPermissionCodes(role.getPermissionCodes().stream()
                        .filter(code -> !permCodes.contains(code))
                        .toList());
                sysRoleMapper.updateById(role);
            }
            // 刷新受影响用户会话
            List<Long> roleIds = roles.stream().map(SysRole::getId).toList();
            List<Long> userIds = sysUserRoleMapper.selectList(
                            new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getRoleId, roleIds))
                    .stream().map(SysUserRole::getUserId).distinct().toList();
            sessionManager.refreshUserSession(userIds);
        }
    }

    /**
     * 查询权限码选项
     * 用于在前端展示权限码选项，每个选项分组下包含多个权限码选项
     * @return 权限码选项分组列表
     */
    @Override
    public List<PermissionOptionGroupVo> options() {
        List<PermissionOptionGroupVo> cached = redisUtil.getList(CACHE_KEY, PermissionOptionGroupVo.class);
        if (cached != null) {
            return cached;
        }
        List<SysPermission> permissions = sysPermissionMapper.selectList(
                new LambdaQueryWrapper<SysPermission>()
                        .eq(SysPermission::getStatus, 1)
                        .orderByAsc(SysPermission::getSortOrder)
        );

        // 按模块分组
        Map<String, List<SysPermission>> grouped = permissions.stream()
                .collect(Collectors.groupingBy(SysPermission::getModuleCode, LinkedHashMap::new, Collectors.toList()));

        List<PermissionOptionGroupVo> result = grouped.entrySet().stream().map(entry -> {
            PermissionOptionGroupVo group = new PermissionOptionGroupVo();
            group.setGroup(entry.getKey());
            List<PermissionOptionGroupVo.PermissionCodeItem> codes = entry.getValue().stream().map(p -> {
                PermissionOptionGroupVo.PermissionCodeItem item = new PermissionOptionGroupVo.PermissionCodeItem();
                item.setCode(p.getPermissionCode());
                item.setLabel(p.getPermissionName());
                return item;
            }).toList();
            group.setCodes(codes);
            return group;
        }).toList();
        redisUtil.setList(CACHE_KEY, result, CACHE_TTL);
        return result;
    }
}