package com.qiheng.erp.system.service;

import com.qiheng.erp.system.domain.dto.SysDeptDto;
import com.qiheng.erp.system.domain.entity.SysDept;
import com.baomidou.mybatisplus.extension.service.IService;
import com.qiheng.erp.system.domain.vo.DeptOptionVo;
import jakarta.validation.Valid;

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

    /**
     * 修改部门状态
     * <p>停用：级联停用自己及所有下级</p>
     * <p>启用：校验所有上级已启用</p>
     *
     * @param deptId 部门ID
     * @param status 目标状态：1 启用，0 停用
     */
    void updateStatus(Long deptId, Integer status);

    /**
     * 批量修改部门状态
     *
     * @param deptIds 部门ID列表
     * @param status  目标状态：1 启用，0 停用
     */
    void batchUpdateStatus(List<Long> deptIds, Integer status);

    /**
     * 批量删除部门
     * @param deptIds 部门ID列表
     */
    void batchDelete(List<String> deptIds);

    /**
     * 删除部门
     * @param deptId 部门ID
     */
    void delete(Long deptId);

    /**
     * 查询部门详情
     * @param deptId 部门ID
     * @return 部门详情
     */
    SysDeptDto getDetails(Long deptId);

    /**
     * 更新部门信息
     * @param deptId 部门ID
     * @param request 部门信息
     * @return 更新后的部门信息
     */
    SysDeptDto updateDept(Long deptId, @Valid SysDeptDto request);

    /**
     * 查询部门下拉选项列表
     * @return 部门下拉选项列表
     */
    List<DeptOptionVo> getDeptOptions();

}