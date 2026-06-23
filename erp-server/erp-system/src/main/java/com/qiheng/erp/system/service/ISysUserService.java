package com.qiheng.erp.system.service;

import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.system.domain.dto.SysUserPageDto;
import com.qiheng.erp.system.domain.entity.SysUser;
import com.qiheng.erp.system.domain.vo.SysUserVo;
import com.baomidou.mybatisplus.extension.service.IService;
import jakarta.validation.constraints.NotNull;

/**
 * <p>
 * 用户表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
public interface ISysUserService extends IService<SysUser> {

    /**
     * 用户分页查询
     *
     * @param dto 查询条件（含分页参数）
     * @return 分页结果
     */
    PageResult<SysUserVo> page(SysUserPageDto dto);

    /**
     * 用户详情查询
     * @param userId 用户ID
     * @return 用户详情
     */
    SysUserVo getDetailById(@NotNull Long userId);
}