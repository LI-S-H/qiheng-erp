package com.qiheng.erp.system.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.system.domain.dto.SysRoleBatchStatusDto;
import com.qiheng.erp.system.domain.dto.SysRolePageDto;
import com.qiheng.erp.system.domain.entity.SysRole;
import com.qiheng.erp.system.domain.vo.RoleOptionVo;
import com.qiheng.erp.system.domain.vo.SysRoleVo;
import com.qiheng.erp.system.service.ISysRoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 角色表 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
@RestController
@RequestMapping("/system/roles")
@Slf4j
@Tag(name = "角色表")
public class SysRoleController {

    @Autowired
    private ISysRoleService sysRoleService;

    /**
     * 分页查询角色列表
     *
     * @param dto 分页查询条件
     * @return 角色列表
     */
    @GetMapping
    @Operation(summary = "分页查询角色列表")
    public Result<PageResult<SysRoleVo>> page(@Valid SysRolePageDto dto) {
        StpUtil.checkPermission("system:role:query");
        log.info("角色分页查询，参数: {}", dto);
        return Result.ok(sysRoleService.page(dto));
    }

    /**
     * 根据角色ID查询角色详情
     *
     * @param roleId 角色ID
     * @return 角色详情
     */
    @GetMapping("/{roleId}")
    @Operation(summary = "根据角色ID查询角色详情")
    public Result<SysRoleVo> get(@PathVariable Long roleId) {
        StpUtil.checkPermission("system:role:query");
        log.info("根据角色ID查询角色详情，参数: {}", roleId);
        SysRoleVo vo = sysRoleService.getDetailById(roleId);
        return Result.ok(vo);
    }

    /**
     * 新增角色
     *
     * @param sysRole 角色信息
     * @return sysRoleVo 新增的角色信息
     */
    @PostMapping
    @Operation(summary = "新增角色")
    public Result<SysRoleVo> create(@RequestBody @Valid SysRole sysRole) {
        StpUtil.checkPermission("system:role:manage");
        log.info("新增角色，参数: {}", sysRole);
        SysRoleVo vo = sysRoleService.add(sysRole);
        return Result.ok(vo);
    }

    /**
     * 批量修改角色状态
     *
     * @param dto 批量状态修改请求
     * @return void
     */
    @PatchMapping("/batch/status")
    @Operation(summary = "批量修改角色状态")
    public Result<Void> batchUpdateStatus(@Valid @RequestBody SysRoleBatchStatusDto dto) {
        StpUtil.checkPermission("system:role:manage");
        log.info("批量修改角色状态，参数: {}", dto);
        sysRoleService.batchUpdateStatus(dto.getRoleIds(), dto.getStatus());
        return Result.ok();
    }

    /**
     * 修改角色状态
     *
     * @param roleId 角色ID
     * @param status 目标状态：1启用，0禁用
     * @return void
     */
    @PatchMapping("/{roleId}/status")
    @Operation(summary = "修改角色状态")
    public Result<Void> updateStatus(@PathVariable Long roleId,@RequestBody Map<String, Integer> status) {
        StpUtil.checkPermission("system:role:manage");
        Integer i = status.get("status");
        if (i == null || i < 0 || i > 1) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        log.info("修改角色状态，参数: roleId={}, status={}", roleId, i);
        sysRoleService.updateStatus(roleId, i);
        return Result.ok();
    }

    /**
     * 批量删除角色
     *
     * @param roleIds 角色ID列表
     * @return void
     */
    @PostMapping("/batch/delete")
    @Operation(summary = "批量删除角色")
    public Result<Void> batchDelete(@RequestBody Map<String, List<String>> roleIds) {
        StpUtil.checkPermission("system:role:manage");
        List<String> list = roleIds.get("roleIds");
        if (list.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        log.info("批量删除角色，参数: {}", list);
        sysRoleService.deleteByIds(list);
        return Result.ok();
    }

    /**
     * 删除角色
     * @param roleId 角色ID
     * @return void
     */
    @DeleteMapping("/{roleId}")
    @Operation(summary = "删除角色")
    public Result<Void> delete(@PathVariable Long roleId) {
        StpUtil.checkPermission("system:role:manage");
        log.info("删除角色，参数: {}", roleId);
        sysRoleService.deleteById(roleId);
        return Result.ok();
    }

    /**
     * 更新角色
     * @param roleId 角色ID
     * @param sysRole 角色信息
     * @return void
     */
    @PutMapping("/{roleId}")
    @Operation(summary = "更新角色")
    public Result<SysRoleVo> update(@PathVariable Long roleId, @RequestBody @Valid SysRole sysRole) {
        StpUtil.checkPermission("system:role:manage");
        log.info("更新角色，参数: roleId={}, sysRole={}", roleId, sysRole);
        SysRoleVo vo = sysRoleService.updateRole(roleId, sysRole);
        return Result.ok(vo);
    }

    /**
     * 修改角色权限码
     * @param roleId 角色ID
     * @param permissionCodes 目标权限码列表
     * @return void
     */
    @PatchMapping("/{roleId}/permissions")
    @Operation(summary = "修改角色权限码")
    public Result<Void> updatePermissionCodes(@PathVariable Long roleId, @RequestBody Map<String, List<String>> permissionCodes) {
        StpUtil.checkPermission("system:role:manage");
        List<String> list = permissionCodes.get("permissionCodes");
        if (list.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        log.info("修改角色权限码，参数: roleId={}, permissionCodes={}", roleId, list);
        sysRoleService.updatePermissionCodes(roleId, list);
        return Result.ok();
    }

    /**
     * 查询角色权限码选项
     * @return 角色权限码选项分组列表
     */
    @GetMapping("/options")
    @Operation(summary = "查询角色权限码选项")
    public Result<List<RoleOptionVo>> options() {
        StpUtil.checkPermission("system:role:query");
        log.info("查询角色权限码选项");
        List<RoleOptionVo> voList = sysRoleService.getPermissionCodeOptions();
        return Result.ok(voList);
    }
}