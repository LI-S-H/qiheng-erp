package com.qiheng.erp.system.service;

import com.qiheng.erp.system.domain.dto.SysDeptDto;
import com.qiheng.erp.system.domain.entity.SysDept;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 部门表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
public interface ISysDeptService extends IService<SysDept> {

    /**
     * 查询部门列表，附带用户数量统计
     *
     * @param deptName 部门名称筛选
     * @param status   状态筛选
     * @return 部门列表（含 userCount）
     */
    List<SysDeptDto> listWithUserCount(String deptName, Integer status);

    /**
     * 保存部门
     * @param dept 部门信息
     * @return 保存后的部门信息（含 deptId）
     */
    SysDeptDto saveDept(SysDept dept);
}