package com.qiheng.erp.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.system.domain.dto.SysDeptDto;
import com.qiheng.erp.system.domain.entity.SysDept;
import com.qiheng.erp.system.domain.entity.SysUser;
import com.qiheng.erp.system.mapper.SysDeptMapper;
import com.qiheng.erp.system.mapper.SysUserMapper;
import com.qiheng.erp.system.service.ISysDeptService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 部门表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
@Slf4j
@Service
public class SysDeptServiceImpl extends ServiceImpl<SysDeptMapper, SysDept> implements ISysDeptService {
    @Autowired
    private SysDeptMapper sysDeptMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 查询部门列表（含 userCount）
     * @param deptName 部门名称筛选
     * @param status   状态筛选
     * @return 部门列表（含 userCount）
     */
    @Override
    public List<SysDeptDto> listWithUserCount(String deptName, Integer status) {
        return sysDeptMapper.selectDeptListWithUserCount(deptName, status);
    }

    /**
     * 保存部门
     * @param dept 部门信息
     * @return 保存后的部门信息（含 deptId）
     */
    @Override
    public SysDeptDto saveDept(SysDept dept) {
        //校验用户是否有保存部门的权限
        StpUtil.checkPermission("system:");
        log.info("保存部门: {}", dept);
        if (dept.getParentId() == null || dept.getParentId() == 0) {
            dept.setAncestors("");
        } else {
            String ancestors = this.getById(dept.getParentId()).getAncestors();
            dept.setAncestors(ancestors + "," + dept.getParentId());
        }
        //执行保存或更新操作
        saveOrUpdate(dept);
        //返回保存后的部门信息
        SysDeptDto dto = BeanUtil.copyProperties(dept, SysDeptDto.class);
        //补全属性
        dto.setDeptId(String.valueOf(dept.getId()));
        dto.setCreateTime(dept.getCreateTime());
        dto.setUpdateTime(dept.getUpdateTime());
        dto.setUserCount(0);
        return dto;
    }

    /**
     * 修改部门状态
     * <p>停用：级联停用自己及所有下级</p>
     * <p>启用：校验所有上级已启用</p>
     * @param deptId 部门ID
     * @param status 目标状态：1 启用，0 停用
     */
    @Override
    public void updateStatus(Long deptId, Integer status) {
        //获取部门锁，启用时添加祖先锁
        RLock multiLock = acquireDeptLocks(Collections.singletonList(deptId), status == 1);
        try {
            batchUpdateStatus(List.of(deptId), status);
        } finally {
            if (multiLock.isHeldByCurrentThread()) {
                multiLock.unlock();
            }
        }
    }

    /**
     * 批量修改部门状态
     * @param deptIds 部门ID列表
     * @param status 目标状态：1 启用，0 停用
     */
    @Override
    public void batchUpdateStatus(List<Long> deptIds, Integer status) {
        //获取部门锁，启用时添加祖先锁
        RLock multiLock = acquireDeptLocks(deptIds, status == 1);
        try {
            doBatchUpdateStatus(deptIds, status);
        } finally {
            if (multiLock.isHeldByCurrentThread()) {
                multiLock.unlock();
            }
        }
    }

    /**
     * 批量删除部门
     * @param deptIds 部门ID列表
     */
    @Override
    public void batchDelete(List<String> deptIds) {
        List<Long> deptIdLongs = deptIds.stream().map(Long::valueOf).toList();
        //获取部门锁，不添加祖先锁
        RLock multiLock = acquireDeptLocks(deptIdLongs,false);
        try {
            doBatchDelete(deptIds);
        } finally {
            if (multiLock.isHeldByCurrentThread()) {
                multiLock.unlock();
            }
        }
    }



    /**
     * 执行批量删除部门
     * @param deptIds 部门ID列表
     */
    private void doBatchDelete(List<String> deptIds) {
        //查询是否有下级部门存在
        Long count = sysDeptMapper.selectCount(new LambdaQueryWrapper<SysDept>()
                .in(SysDept::getParentId, deptIds)
                .eq(SysDept::getStatus, 1));
        if (count > 0) {
            throw new BizException(ErrorCode.CHILD_DEPT_EXISTS);
        }
        //查询是否有用户存在该部门
        count = sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .in(SysUser::getDeptId, deptIds)
                .eq(SysUser::getStatus, 1));
        if (count > 0) {
            throw new BizException(ErrorCode.DEPT_HAS_USERS);
        }
        //删除部门
        sysDeptMapper.deleteByIds(deptIds);
    }

    /**
     * 执行批量修改部门状态
     * @param deptIds 部门ID列表
     * @param status 目标状态：1 启用，0 停用
     */
    private void doBatchUpdateStatus(List<Long> deptIds, Integer status) {
        List<SysDept> depts = sysDeptMapper.selectByIds(deptIds);
        //校验部门是否存在
        if (depts.size() < deptIds.size()) {
            throw new BizException(ErrorCode.DEPT_NOT_FOUND);
        }
        //级联停用部门：将自己及所有下级部门状态设为停用
        if (status == 0) {
            List<String> idStrings = deptIds.stream().map(String::valueOf).toList();
            sysDeptMapper.disableDeptsCascade(idStrings);
            return;
        }
        Set<Long> ancestorIds = parseAncestorIds(depts);
        //过滤掉部门ID列表中的部门ID，避免重复校验自己
        deptIds.forEach(ancestorIds::remove);
        if (!ancestorIds.isEmpty()) {
            int disabledCount = sysDeptMapper.countDisabledByIds(ancestorIds);
            if (disabledCount > 0) {
                throw new BizException(ErrorCode.PARENT_DEPT_DISABLED);
            }
        }
        sysDeptMapper.enableDepts(deptIds);
    }

    /**
     * 获取部门锁
     * @param deptIds 部门ID列表
     * @return 多锁对象
     */
    private RLock acquireDeptLocks(List<Long> deptIds, boolean isAddAncestorLock) {
        //确保锁的顺序，避免死锁
        Set<Long> lockIds = new TreeSet<>(deptIds);
        List<SysDept> depts = sysDeptMapper.selectByIds(deptIds);
        if (!depts.isEmpty()) {
            //添加所有部门的祖先ID到锁ID列表，避免启用部门时，上级部门状态被修改
            if (isAddAncestorLock) {
                lockIds.addAll(parseAncestorIds(depts));
            }else{
                lockIds.addAll(deptIds);
            }
        }
        //获取多锁集合
        List<RLock> locks = lockIds.stream()
                .map(id -> redissonClient.getLock("dept:lock:" + id))
                .toList();
        //获取多锁对象
        RLock multiLock = redissonClient.getMultiLock(locks.toArray(new RLock[0]));
        try {
            //尝试获取锁，超时3秒，等待30秒
            if (!multiLock.tryLock(3, 30, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "操作频繁，请稍后重试");
            }
        } catch (InterruptedException e) {
            //中断线程，避免阻塞
            Thread.currentThread().interrupt();
            //抛出中断异常，通知调用者操作被中断
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "操作被中断");
        }
        return multiLock;
    }

    /**
     * 解析部门的祖先ID列表
     * @param depts 部门列表
     * @return 祖先ID列表
     */
    private Set<Long> parseAncestorIds(List<SysDept> depts) {
        // 将多个部门的 ancestors 字段（如 "0,100,200"）拆分、去重后返回所有上级部门ID
        // 示例：部门A ancestors="0,100,200"，部门B ancestors="0,200,300" → 结果：{100, 200, 300}
        return depts.stream()
                .map(SysDept::getAncestors)
                // 过滤空字符串和null
                .filter(a -> a != null && !a.isEmpty())
                // 按逗号拆分，flatMap 将多个数组拍平成一个流
                .flatMap(a -> Arrays.stream(a.split(",")))
                // 去掉空字符串和虚拟根"0"
                .filter(s -> !s.isEmpty() && !s.equals("0"))
                // 转成 Long
                .map(Long::valueOf)
                // Collectors.toSet() 自动去重
                .collect(Collectors.toSet());
    }
}