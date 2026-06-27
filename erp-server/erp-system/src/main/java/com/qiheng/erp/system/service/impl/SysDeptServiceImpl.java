package com.qiheng.erp.system.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.common.util.RedisUtil;
import com.qiheng.erp.system.domain.dto.SysDeptDto;
import com.qiheng.erp.system.domain.entity.SysDept;
import com.qiheng.erp.system.domain.entity.SysUser;
import com.qiheng.erp.system.domain.vo.DeptOptionVo;
import com.qiheng.erp.system.mapper.SysDeptMapper;
import com.qiheng.erp.system.mapper.SysUserMapper;
import com.qiheng.erp.system.service.ISysDeptService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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
    private static final String CACHE_KEY = "system:options:depts";
    private static final Duration CACHE_TTL = Duration.ofHours(1);

    @Autowired
    private SysDeptMapper sysDeptMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private RedisUtil redisUtil;

    /**
     * 查询部门列表（含 userCount）
     *
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
     *
     * @param dept 部门信息
     * @return 保存后的部门信息（含 deptId）
     */
    @Override
    @DistributedLock(key = "'dept:lock:global'")
    public SysDeptDto saveDept(SysDept dept) {
        StpUtil.checkPermission("system:");
        log.info("保存部门: {}", dept);

        if (dept.getParentId() == null || dept.getParentId() == 0) {
            dept.setAncestors("0");
        } else {
            SysDept parent = this.getById(dept.getParentId());
            if (parent == null) {
                throw new BizException(ErrorCode.DEPT_NOT_FOUND.getCode(), "上级部门不存在");
            }
            if (parent.getStatus() == 0) {
                throw new BizException(ErrorCode.PARENT_DEPT_DISABLED);
            }
            dept.setAncestors(parent.getAncestors() + "," + dept.getParentId());
        }
        saveOrUpdate(dept);
        redisUtil.delete(CACHE_KEY);
        SysDeptDto dto = BeanUtil.copyProperties(dept, SysDeptDto.class);
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
     *
     * @param deptId 部门ID
     * @param status 目标状态：1 启用，0 停用
     */
    @Override
    @DistributedLock(key = "'dept:lock:global'")
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long deptId, Integer status) {
        doBatchUpdateStatus(List.of(deptId), status);
    }

    /**
     * 批量修改部门状态
     *
     * @param deptIds 部门ID列表
     * @param status  目标状态：1 启用，0 停用
     */
    @Override
    @DistributedLock(key = "'dept:lock:global'")
    @Transactional(rollbackFor = Exception.class)
    public void batchUpdateStatus(List<Long> deptIds, Integer status) {
        doBatchUpdateStatus(deptIds, status);
    }

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
            redisUtil.delete(CACHE_KEY);
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
        redisUtil.delete(CACHE_KEY);
    }

    /**
     * 批量删除部门
     *
     * @param deptIds 部门ID列表
     */
    @Override
    @DistributedLock(key = "'dept:lock:global'")
    public void batchDelete(List<String> deptIds) {
        doBatchDelete(deptIds);
    }

    /**
     * 删除部门
     *
     * @param deptId 部门ID
     */
    @Override
    @DistributedLock(key = "'dept:lock:global'")
    public void delete(Long deptId) {
        doBatchDelete(List.of(deptId.toString()));
    }

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
        redisUtil.delete(CACHE_KEY);
    }

    /**
     * 查询部门详情
     *
     * @param deptId 部门ID
     * @return 部门详情
     */
    @Override
    public SysDeptDto getDetails(Long deptId) {
        SysDept dept = getById(deptId);
        if (dept == null) {
            throw new BizException(ErrorCode.DEPT_NOT_FOUND);
        }
        SysDeptDto dto = new SysDeptDto();
        BeanUtil.copyProperties(dept, dto);
        //补全部门ID
        dto.setDeptId(String.valueOf(deptId));
        //补全用户数量
        dto.setUserCount(Math.toIntExact(sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeptId, deptId)
                .eq(SysUser::getStatus, 1))));
        return dto;
    }

    /**
     * 更新部门信息
     *
     * @param deptId  部门ID
     * @param request 部门信息
     * @return 更新后的部门信息
     */
    @Override
    @DistributedLock(key = "'dept:lock:global'")
    @Transactional(rollbackFor = Exception.class)
    public SysDeptDto updateDept(Long deptId, SysDeptDto request) {
        SysDept dept = sysDeptMapper.selectById(deptId);
        if (dept == null) {
            throw new BizException(ErrorCode.DEPT_NOT_FOUND);
        }

        // 计算目标状态
        Long newParentId = Long.valueOf(request.getParentId());
        Integer newStatus = request.getStatus();
        boolean parentChanged = !newParentId.equals(dept.getParentId());
        boolean statusChanged = !newStatus.equals(dept.getStatus());

        // ===== 先校验所有（不修改任何数据） =====

        // 校验1：上级变更的合法性
        if (parentChanged) {
            validateNewParent(dept, newParentId, newStatus);
        }

        // 校验2：启用时，检查上级部门是否停用
        if (statusChanged && newStatus == 1) {
            validateCanEnable(newParentId);
        }

        // ===== 校验全部通过，安全执行修改 =====

        //修改部门名称
        if (request.getDeptName() != null) {
            dept.setDeptName(request.getDeptName());
        }
        //修改上级
        if (parentChanged) {
            applyChangeParent(dept, newParentId);
        }
        //修改状态
        if (statusChanged) {
            doBatchUpdateStatus(List.of(deptId), newStatus);
            dept.setStatus(newStatus);
        }
        int rows = sysDeptMapper.updateById(dept);
        if (rows == 0) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "数据已被修改，请刷新后重试");
        }
        // 清除缓存
        redisUtil.delete(CACHE_KEY);

        SysDept updated = sysDeptMapper.selectById(deptId);
        SysDeptDto dto = BeanUtil.copyProperties(updated, SysDeptDto.class);
        dto.setDeptId(String.valueOf(updated.getId()));
        dto.setCreateTime(updated.getCreateTime());
        dto.setUpdateTime(updated.getUpdateTime());
        dto.setUserCount(Math.toIntExact(sysUserMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeptId, deptId)
                .eq(SysUser::getStatus, 1))));
        return dto;
    }


    /**
     * 查询部门下拉选项列表
     * @return 部门下拉选项列表
     */
    @Override
    public List<DeptOptionVo> getDeptOptions() {
        List<DeptOptionVo> cached = redisUtil.getList(CACHE_KEY, DeptOptionVo.class);
        if (cached != null) {
            return cached;
        }
        LambdaQueryWrapper<SysDept> query = new LambdaQueryWrapper<SysDept>()
                .select(SysDept::getId, SysDept::getDeptName, SysDept::getParentId, SysDept::getStatus)
                .eq(SysDept::getStatus, 1)
                .orderByAsc(SysDept::getId);
        List<DeptOptionVo> result = sysDeptMapper.selectList(query).stream()
                .map(dept -> {
                    DeptOptionVo vo = BeanUtil.copyProperties(dept, DeptOptionVo.class);
                    vo.setDeptId(String.valueOf(dept.getId()));
                    return vo;
                })
                .collect(Collectors.toList());
        redisUtil.setList(CACHE_KEY, result, CACHE_TTL);
        return result;
    }

    /**
     * 校验上级变更的合法性（不修改任何数据）
     *
     * @param dept        当前部门
     * @param newParentId 新上级部门ID
     * @param newStatus   目标状态（用于判断新上级是否需要启用）
     */
    private void validateNewParent(SysDept dept, Long newParentId, Integer newStatus) {
        if (newParentId.equals(dept.getId())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "上级部门不能是自己");
        }
        if (newParentId == 0) {
            return;
        }
        SysDept newParent = sysDeptMapper.selectById(newParentId);
        if (newParent == null) {
            throw new BizException(ErrorCode.DEPT_NOT_FOUND.getCode(), "上级部门不存在");
        }
        // 如果目标是启用状态，新上级必须是启用的
        if (newStatus == 1 && newParent.getStatus() == 0) {
            throw new BizException(ErrorCode.PARENT_DEPT_DISABLED.getCode(), "新上级部门已停用，无法启用");
        }
        // 新上级不能是自己的下级（防止循环引用）
        if (newParent.getAncestors() != null && newParent.getAncestors().contains(String.valueOf(dept.getId()))) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "上级部门不能是自己的下级部门");
        }

    }

    /**
     * 校验是否可以启用（基于新上级链路，不修改任何数据）
     * @param newParentId 新上级部门ID
     */
    private void validateCanEnable(Long newParentId) {
        if (newParentId == 0) {
            return;
        }
        SysDept newParent = sysDeptMapper.selectById(newParentId);
        if (newParent.getStatus() == 0) {
            throw new BizException(ErrorCode.PARENT_DEPT_DISABLED);
        }
    }

    /**
     * 执行上级变更（校验已通过，安全修改数据）
     *
     * @param dept        当前部门
     * @param newParentId 新上级部门ID
     */
    private void applyChangeParent(SysDept dept, Long newParentId) {
        // 保存旧的 ancestors 前缀
        String oldAncestors = dept.getAncestors();
        if (newParentId == 0) {
            // 顶级部门
            dept.setParentId(0L);
            dept.setAncestors("0");
        } else {
            // 非顶级部门
            SysDept newParent = sysDeptMapper.selectById(newParentId);
            String newAncestors = newParent.getAncestors() + "," + newParentId;
            dept.setParentId(newParentId);
            dept.setAncestors(newAncestors);
        }
        updateDescendantsAncestors(dept.getId(), oldAncestors, dept.getAncestors());
    }

    /**
     * 联动更新所有下级部门的 ancestors
     * <p>将所有下级的 ancestors 中的 oldPrefix 替换为 newPrefix</p>
     * <p>例：部门A从X下移到Y下，下级B的 ancestors 从 "0,X,A" 变为 "0,Y,A"</p>
     *
     * @param deptId       上级部门ID
     * @param oldAncestors 旧的 ancestors 前缀
     * @param newAncestors 新的 ancestors 前缀
     */
    private void updateDescendantsAncestors(Long deptId, String oldAncestors, String newAncestors) {
        List<SysDept> descendants = sysDeptMapper.selectList(new LambdaQueryWrapper<SysDept>()
                .like(SysDept::getAncestors, deptId));
        for (SysDept descendant : descendants) {
            // 替换前缀：把 "0,X,A" 替换为 "0,Y,A"
            descendant.setAncestors(descendant.getAncestors().replace(oldAncestors, newAncestors));
            sysDeptMapper.updateById(descendant);
        }
    }

    /**
     * 解析部门的祖先ID列表（包含虚拟根"0"）
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