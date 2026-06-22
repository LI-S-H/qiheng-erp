package com.qiheng.erp.system.mapper;

import com.qiheng.erp.system.domain.dto.SysDeptDto;
import com.qiheng.erp.system.domain.entity.SysDept;
import com.github.yulichang.base.MPJBaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/**
 * <p>
 * 部门表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
public interface SysDeptMapper extends MPJBaseMapper<SysDept> {

    /**
     * 查询部门列表，附带用户数量统计（子查询）
     */
    List<SysDeptDto> selectDeptListWithUserCount(@Param("deptName") String deptName,
                                                  @Param("status") Integer status);

    /**
     * 批量级联停用：将指定部门及其所有下级部门状态设为停用
     *
     * @param deptIds 部门ID列表（字符串形式，避免精度问题）
     * @return 受影响行数
     */
    int disableDeptsCascade(@Param("deptIds") List<String> deptIds);

    /**
     * 批量启用：将指定部门状态设为启用
     *
     * @param deptIds 部门ID列表
     * @return 受影响行数
     */
    int enableDepts(@Param("deptIds") List<Long> deptIds);

    /**
     * 根据上级部门ID集合，统计其中已停用的数量
     *
     * @param ids 上级部门ID集合
     * @return 已停用的上级部门数量
     */
    int countDisabledByIds(@Param("ids") Collection<Long> ids);
}