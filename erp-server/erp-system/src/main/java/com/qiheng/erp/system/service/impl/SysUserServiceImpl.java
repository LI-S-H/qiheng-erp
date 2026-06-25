package com.qiheng.erp.system.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.util.PasswordUtil;
import com.qiheng.erp.system.domain.dto.SysUserPageDto;
import com.qiheng.erp.system.domain.dto.SysUserStatusUpdateDto;
import com.qiheng.erp.system.domain.dto.UserPasswordUpdateDto;
import com.qiheng.erp.system.domain.dto.UserRoleDto;
import com.qiheng.erp.system.domain.entity.SysDept;
import com.qiheng.erp.system.domain.entity.SysRole;
import com.qiheng.erp.system.domain.entity.SysUser;
import com.qiheng.erp.system.domain.entity.SysUserRole;
import com.qiheng.erp.system.domain.vo.SysUserVo;
import com.qiheng.erp.system.manager.SessionManager;
import com.qiheng.erp.system.mapper.SysRoleMapper;
import com.qiheng.erp.system.mapper.SysUserMapper;
import com.qiheng.erp.system.mapper.SysUserRoleMapper;
import com.qiheng.erp.system.service.ISysUserRoleService;
import com.qiheng.erp.system.service.ISysUserService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.*;
import java.util.concurrent.TimeUnit;
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

    @Autowired
    private PasswordUtil passwordUtil;

    @Autowired
    private ISysUserRoleService sysUserRoleService;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private SessionManager sessionManager;

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

    /**
     * 用户新增
     * @param sysUser 用户实体
     * @return 新增结果
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public SysUserVo saveUser(SysUser sysUser) {
        //补全信息
        sysUser.setPasswordHash(passwordUtil.encode(sysUser.getPassword()));
        //新增
        sysUserMapper.insert(sysUser);
        //补全中间表
        List<SysUserRole> userRoles = sysUser.getRoleIds().stream().map(roleId ->
                new SysUserRole().setUserId(sysUser.getId()).setRoleId(roleId)
        ).toList();
        //批量新增
        sysUserRoleService.saveBatch(userRoles);
        //查询新增用户详情并填充角色信息和部门信息
        return getDetailById(sysUser.getId());
    }

    /**
     * 批量更新用户状态
     * @param dto 更新状态条件
     */
    @Override
    public void updateStatus(SysUserStatusUpdateDto dto) {
        //构建更新条件
        LambdaUpdateWrapper<SysUser> wrapper = new LambdaUpdateWrapper<SysUser>()
                .set(SysUser::getStatus, dto.getStatus())
                .in(SysUser::getId, dto.getUserIds());
        //执行更新
        sysUserMapper.update(wrapper);
        //停用用户时，强制踢下线
        if (dto.getStatus() == 0) {
            sessionManager.kickOffline(dto.getUserIds().stream().map(Long::valueOf).toList());
        }
    }

    /**
     * 更新用户状态
     * @param userId 用户ID
     * @param status 状态
     */
    @Override
    public void updateStatusById(Long userId, Integer status) {
        //构建更新条件
        LambdaUpdateWrapper<SysUser> wrapper = new LambdaUpdateWrapper<SysUser>()
                .set(SysUser::getStatus, status)
                .eq(SysUser::getId, userId);
        //执行更新
        sysUserMapper.update(wrapper);
        //停用用户时，强制踢下线
        if (status == 0) {
            sessionManager.kickOffline(userId);
        }
    }

    /**
     * 批量更新用户密码
     * @param dto 更新密码条件
     */
    @Override
    public void updatePasswordByIds(UserPasswordUpdateDto dto) {
        //构建更新条件
        LambdaUpdateWrapper<SysUser> wrapper = new LambdaUpdateWrapper<SysUser>()
                .set(SysUser::getPasswordHash, passwordUtil.encode(dto.getPassword()))
                .in(SysUser::getId, dto.getUserIds());
        //执行更新
        sysUserMapper.update(wrapper);
        //重置密码后，强制踢下线，用户需用新密码重新登录
        sessionManager.kickOffline(dto.getUserIds().stream().map(Long::valueOf).toList());
    }

    /**
     * 更新用户密码
     * @param userId 用户ID
     * @param password 明文密码
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updatePasswordById(Long userId, String password) {
        //构建更新条件
        LambdaUpdateWrapper<SysUser> wrapper = new LambdaUpdateWrapper<SysUser>()
                .set(SysUser::getPasswordHash, passwordUtil.encode(password))
                .eq(SysUser::getId, userId);
        //执行更新
        sysUserMapper.update(wrapper);
        //踢下线用户，强制其重新登录，使用新密码登录
        sessionManager.kickOffline(userId);
    }

    /**
     * 更新用户
     * @param userId 用户ID
     * @param sysUser 用户实体
     * @return 更新结果
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    @DistributedLock(key = "'sys:user:' + #userId")
    public SysUserVo updateUser(Long userId, SysUser sysUser) {
        //查询用户是否存在
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND.getCode(), "用户不存在");
        }
        //更新用户信息
        sysUser.setId(userId);
        sysUserMapper.updateById(sysUser);
        //更新用户角色关系
        sysUserRoleService.remove(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        List<SysUserRole> userRoles = sysUser.getRoleIds().stream().map(roleId ->
                new SysUserRole().setUserId(userId).setRoleId(roleId)
        ).toList();
        sysUserRoleService.saveBatch(userRoles);
        //刷新用户Session
        sessionManager.refreshUserSession(userId);
        //查询更新后的用户详情并填充角色信息和部门信息
        return getDetailById(userId);
    }

    /**
     * 更新用户角色
     * @param userId 用户ID
     * @param roleIds 更新角色ID列表
     */
    @DistributedLock(key ="'sys:user:' + #userId")
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void updateRoles(Long userId,List<String> roleIds) {
        //查询用户是否存在
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND.getCode(), "用户不存在");
        }
        //校验角色状态：直接查是否有停用的角色
        List<Long> roleIdLongs = roleIds.stream().map(Long::valueOf).toList();
        Long disabledCount = sysRoleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .in(SysRole::getId, roleIdLongs)
                .eq(SysRole::getStatus, 0));
        if (disabledCount > 0) {
            throw new BizException(ErrorCode.ROLE_DISABLED);
        }
        //删除用户角色关系
        sysUserRoleService.remove(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        //批量新增
        List<SysUserRole> userRoles = roleIds.stream().map(roleId ->
                new SysUserRole().setUserId(userId).setRoleId(Long.parseLong(roleId))
        ).toList();
        sysUserRoleService.saveBatch(userRoles);
        //刷新用户Session
        sessionManager.refreshUserSession(userId);
    }

    /**
     * 批量删除用户
     * @param ids 用户ID列表
     */
    @Transactional(rollbackFor = Exception.class)
    @Override
    public void deleteBatch(List<String> ids) {
        //加分布式锁
        List<RLock> locks = new ArrayList<>();
        for (String id : ids) {
            RLock lock = redissonClient.getLock("sys:user:" + id);
            try {
                if (!lock.tryLock(0,30, TimeUnit.SECONDS)) {
                    locks.forEach(l -> {
                        if (l.isHeldByCurrentThread())
                            l.unlock();
                    });
                    throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "操作失败，请稍后再试");
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            locks.add(lock);
        }
        //删除用户角色关系
        sysUserRoleService.remove(new LambdaQueryWrapper<SysUserRole>().in(SysUserRole::getUserId, ids));
        //删除用户
        removeByIds(ids);
        //踢下线用户Session
        sessionManager.kickOffline(ids.stream().map(Long::valueOf).toList());
        //注册事务同步，确保在事务提交后释放锁
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                locks.forEach(lock -> {
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                });
            }
        });
    }
}