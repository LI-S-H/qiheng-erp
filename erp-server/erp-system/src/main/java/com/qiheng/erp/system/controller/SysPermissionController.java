package com.qiheng.erp.system.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.system.domain.dto.SysPermissionBatchStatusDto;
import com.qiheng.erp.system.domain.dto.SysPermissionPageDto;
import com.qiheng.erp.system.domain.entity.SysPermission;
import com.qiheng.erp.system.domain.vo.SysPermissionVo;
import com.qiheng.erp.system.manager.SessionManager;
import com.qiheng.erp.system.service.ISysPermissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 权限码目录表 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
@RestController
@RequestMapping("/system/permissions")
@Slf4j
@Tag(name = "权限码目录表")
public class SysPermissionController {
    @Autowired
    private ISysPermissionService sysPermissionService;

    /**
     * 分页查询权限码列表
     * @param dto 分页查询请求
     * @return 权限码列表
     */
    @GetMapping
    @Operation(summary = "分页查询权限码")
    public Result<PageResult<SysPermissionVo>> page(SysPermissionPageDto dto) {
        StpUtil.checkPermission("system:permission:query");
        log.info("权限码分页查询，参数: {}", dto);
        return Result.ok(sysPermissionService.page(dto));
    }

    /**
     * 根据ID查询权限码详情
     * @param permissionId 权限码ID
     * @return 权限码详情
     */
    @GetMapping("/{permissionId}")
    @Operation(summary = "查询权限码详情")
    public Result<SysPermissionVo> getById(@PathVariable Long permissionId) {
        StpUtil.checkPermission("system:permission:query");
        log.info("查询权限码详情，参数: {}", permissionId);
        return Result.ok(sysPermissionService.getDetailById(permissionId));
    }

    /**
     * 新增权限码
     * @param permission 权限码信息
     * @return 新增结果
     */
    @PostMapping
    @Operation(summary = "新增权限码")
    public Result<SysPermissionVo> create(@Valid @RequestBody SysPermission permission) {
        StpUtil.checkPermission("system:permission:create");
        log.info("新增权限码，参数: {}", permission);
        SysPermissionVo vo = sysPermissionService.insert(permission);
        return Result.ok(vo);
    }

    /**
     * 更新权限码
     * @param permissionId 权限码ID
     * @param permission 权限码信息
     * @return 更新结果
     */
    @PutMapping("/{permissionId}")
    @Operation(summary = "更新权限码")
    public Result<SysPermissionVo> update(@PathVariable Long permissionId, @Valid @RequestBody SysPermission permission) {
        StpUtil.checkPermission("system:permission:update");
        log.info("更新权限码，参数: {}", permission);
        permission.setId(permissionId);
        SysPermissionVo vo = sysPermissionService.updatePermission(permission);
        return Result.ok(vo);
    }

    /**
     * 批量删除权限码
     * @param permissionIds 权限码ID列表
     * @return 删除结果
     */
    @PostMapping("/batch/delete")
    @Operation(summary = "批量删除权限码")
    public Result<Void> batchDelete(@RequestBody Map<String,List<String>> permissionIds) {
        StpUtil.checkPermission("system:permission:manage");
        List<String> ids = permissionIds.get("permissionIds");
        if (ids == null || ids.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        log.info("批量删除权限码，参数: {}", ids);
        sysPermissionService.batchDeletePermissions(ids);
        return Result.ok();
    }

    /**
     * 删除权限码
     * @param permissionId 权限码ID
     * @return 删除结果
     */
    @DeleteMapping("/{permissionId}")
    @Operation(summary = "删除权限码")
    public Result<Void> delete(@PathVariable String permissionId) {
        StpUtil.checkPermission("system:permission:manage");
        log.info("删除权限码，参数: {}", permissionId);
        sysPermissionService.batchDeletePermissions(Collections.singletonList(permissionId));
        return Result.ok();
    }

    /**
     * 更新权限码状态
     * @param permissionId 权限码ID
     * @param status 状态
     * @return 更新结果
     */
    @PatchMapping("/{permissionId}/status")
    @Operation(summary = "更新权限码状态")
    public Result<Void> updatePermissionStatus(@PathVariable String permissionId, @RequestBody Map<String,Integer> status) {
        StpUtil.checkPermission("system:permission:manage");
        Integer statusValue = status.get("status");
        if (statusValue == null||statusValue < 0 || statusValue > 1) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        log.info("更新权限码状态，参数: {}, {}", permissionId, statusValue);
        sysPermissionService.updatePermissionStatus(permissionId, statusValue);
        return Result.ok();
    }

    /**
     * 批量更新权限码状态
     * @param dto 批量更新权限码状态请求
     * @return 更新结果
     */
    @PatchMapping("/batch/status")
    @Operation(summary = "批量更新权限码状态")
    public Result<Void> batchUpdateStatus(@Valid @RequestBody SysPermissionBatchStatusDto dto) {
        StpUtil.checkPermission("system:permission:manage");
        log.info("批量更新权限码状态，参数: {}", dto);
        sysPermissionService.batchUpdateStatus(dto.getPermissionIds(), dto.getStatus());
        return Result.ok();

    }
}
