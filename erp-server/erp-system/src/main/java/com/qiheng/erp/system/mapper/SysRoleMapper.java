package com.qiheng.erp.system.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.system.domain.entity.SysRole;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 角色表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
public interface SysRoleMapper extends MPJBaseMapper<SysRole> {

    List<SysRole> selectByPermissionCodes(@Param("permissionCodes") List<String> permissionCodes);
}