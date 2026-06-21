package com.qiheng.erp.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiheng.erp.system.domain.dto.SysDeptDto;
import com.qiheng.erp.system.domain.entity.SysDept;
import com.github.yulichang.base.MPJBaseMapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 部门表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
public interface SysDeptMapper extends BaseMapper<SysDept> {

    /**
     * 查询部门列表，附带用户数量统计（LEFT JOIN + GROUP BY）
     *
     * @param deptName 部门名称筛选
     * @param status   状态筛选
     * @return 部门列表（含 userCount）
     */
    List<SysDeptDto> selectDeptListWithUserCount(@Param("deptName") String deptName,
                                                  @Param("status") Integer status);
}
