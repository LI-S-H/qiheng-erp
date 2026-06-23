package com.qiheng.erp.system.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.system.domain.dto.SysUserPageDto;
import com.qiheng.erp.system.domain.vo.SysUserVo;
import com.qiheng.erp.system.service.ISysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 用户表 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
@Slf4j
@RestController
@RequestMapping("/system/users")
@Tag(name = "用户管理")
public class SysUserController {
    @Autowired
    private ISysUserService sysUserService;

    /**
     * 用户分页查询
     * @param dto 查询条件（含分页参数）
     * @return 分页结果
     */
    @GetMapping
    @Operation(summary = "用户分页查询")
    public Result<PageResult<SysUserVo>> page(SysUserPageDto dto) {
        StpUtil.checkPermission("system:user:query");
        log.info("用户分页查询，参数: {}", dto);
        PageResult<SysUserVo> result = sysUserService.page(dto);
        return Result.ok(result);
    }

    /**
     * 用户详情查询
     * @param userId 用户ID
     * @return 用户详情
     */
    @GetMapping("/{userId}")
    @Operation(summary = "用户详情查询")
    public Result<SysUserVo> getById(@PathVariable @NotNull Long userId) {
        StpUtil.checkPermission("system:user:query");
        log.info("用户详情查询，参数: {}", userId);
        SysUserVo result = sysUserService.getDetailById(userId);
        return Result.ok(result);
    }


}