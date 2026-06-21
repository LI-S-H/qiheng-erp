package com.qiheng.erp.system.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.qiheng.erp.system.domain.dto.SysDeptDto;
import com.qiheng.erp.system.domain.entity.SysDept;
import com.qiheng.erp.system.mapper.SysDeptMapper;
import com.qiheng.erp.system.service.ISysDeptService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

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

    /**
     * 查询部门列表（含 userCount）
     * @param deptName 部门名称筛选
     * @param status   状态筛选
     * @return 部门列表（含 userCount）
     */
    @Override
    public List<SysDeptDto> listWithUserCount(String deptName, Integer status) {
        return baseMapper.selectDeptListWithUserCount(deptName, status);
    }

    /**
     * 保存部门
     * @param dept 部门信息
     * @return 保存后的部门信息（含 deptId）
     */
    @Override
    public SysDeptDto saveDept(SysDept dept) {
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
}