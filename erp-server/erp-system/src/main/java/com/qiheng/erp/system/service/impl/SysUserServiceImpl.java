package com.qiheng.erp.system.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.system.domain.dto.SysUserPageDto;
import com.qiheng.erp.system.domain.dto.UserRoleDto;
import com.qiheng.erp.system.domain.entity.SysDept;
import com.qiheng.erp.system.domain.entity.SysRole;
import com.qiheng.erp.system.domain.entity.SysUser;
import com.qiheng.erp.system.domain.entity.SysUserRole;
import com.qiheng.erp.system.domain.vo.SysUserVo;
import com.qiheng.erp.system.mapper.SysRoleMapper;
import com.qiheng.erp.system.mapper.SysUserMapper;
import com.qiheng.erp.system.mapper.SysUserRoleMapper;
import com.qiheng.erp.system.service.ISysUserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;


/**
 * <p>
 * 用户表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
@Service
public class SysUserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements ISysUserService {

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private SysRoleMapper sysRoleMapper;

    /**
     * 用户分页查询
     * @param dto 查询条件（含分页参数）
     * @return 分页结果
     */
    @Override
    public PageResult<SysUserVo> page(SysUserPageDto dto) {
        //构建查询条件
        MPJLambdaWrapper<SysUser> wrapper = new MPJLambdaWrapper<SysUser>()
                .selectAs(SysUser::getId, SysUserVo::getUserId)
                .select(SysUser::getUsername)
                .select(SysUser::getRealName)
                .select(SysUser::getDeptId)
                .selectAs(SysDept::getDeptName, SysUserVo::getDeptName)
                .selectAs(SysUser::getIsAdmin, SysUserVo::getIsAdmin)
                .select(SysUser::getStatus)
                .select(SysUser::getLastLoginAt)
                .select(SysUser::getCreateTime)
                .select(SysUser::getUpdateTime)
                .leftJoin(SysDept.class, SysDept::getId, SysUser::getDeptId)
                .like(StrUtil.isNotBlank(dto.getUsername()), SysUser::getUsername, dto.getUsername())
                .like(StrUtil.isNotBlank(dto.getRealName()), SysUser::getRealName, dto.getRealName())
                .eq(dto.getDeptId() != null, SysUser::getDeptId, dto.getDeptId())
                .eq(dto.getStatus() != null, SysUser::getStatus, dto.getStatus())
                .orderByDesc(SysUser::getCreateTime);
        //执行查询
        Page<SysUserVo> result = sysUserMapper.selectJoinPage(dto.toPage(), SysUserVo.class, wrapper);
        fillRoles(result.getRecords());
        return PageResult.of(result.getRecords(), (int) result.getTotal(), (int) result.getCurrent(), (int) result.getSize());
    }

    /**
     * 用户详情查询
     * @param userId 用户ID
     * @return 用户详情
     */
    @Override
    public SysUserVo getDetailById(Long userId) {
        //构建查询条件
        MPJLambdaWrapper<SysUser> wrapper = new MPJLambdaWrapper<SysUser>()
                .selectAs(SysUser::getId, SysUserVo::getUserId)
                .select(SysUser::getUsername)
                .select(SysUser::getRealName)
                .select(SysUser::getDeptId)
                .selectAs(SysDept::getDeptName, SysUserVo::getDeptName)
                .selectAs(SysUser::getIsAdmin, SysUserVo::getIsAdmin)
                .select(SysUser::getStatus)
                .select(SysUser::getLastLoginAt)
                .select(SysUser::getCreateTime)
                .select(SysUser::getUpdateTime)
                .leftJoin(SysDept.class, SysDept::getId, SysUser::getDeptId)
                .eq(SysUser::getId, userId);
        //执行查询
        SysUserVo vo = sysUserMapper.selectJoinOne(SysUserVo.class, wrapper);
        if (vo != null) {
            fillRoles(List.of(vo));
        }
        return vo;
    }

    /**
     * 填充用户角色信息
     * @param voList 用户VO列表
     */
    private void fillRoles(List<SysUserVo> voList) {
        if (voList == null || voList.isEmpty()) {
            return;
        }
        //获取用户ID列表
        Set<Long> userIds = voList.stream().map(SysUserVo::getUserId).collect(Collectors.toSet());
        //查询用户角色关系
        List<UserRoleDto> userRoles = sysUserRoleMapper.selectJoinList(UserRoleDto.class,
                new MPJLambdaWrapper<SysUserRole>()
                        .selectAs(SysUserRole::getUserId, UserRoleDto::getUserId)
                        .selectAs(SysUserRole::getRoleId, UserRoleDto::getRoleId)
                        .selectAs(SysRole::getRoleName, UserRoleDto::getRoleName)
                        .leftJoin(SysRole.class, SysRole::getId, SysUserRole::getRoleId)
                        .in(SysUserRole::getUserId, userIds));
        if (userRoles.isEmpty()) {
            return;
        }
        //构建角色名称映射
        Map<Long, List<UserRoleDto>> userRoleMap = userRoles.stream()
                .collect(Collectors.groupingBy(UserRoleDto::getUserId));
        //填充用户角色信息
        voList.forEach(vo -> {
            List<UserRoleDto> roles = userRoleMap.get(vo.getUserId());
            if (roles != null) {
                vo.setRoleIds(roles.stream().map(UserRoleDto::getRoleId).collect(Collectors.toList()));
                vo.setRoleNames(roles.stream().map(UserRoleDto::getRoleName).filter(Objects::nonNull).collect(Collectors.toList()));
            }
        });
    }
}