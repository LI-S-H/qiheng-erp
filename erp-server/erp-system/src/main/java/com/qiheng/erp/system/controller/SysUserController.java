package com.qiheng.erp.system.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.system.domain.dto.SysUserPageDto;
import com.qiheng.erp.system.domain.dto.SysUserStatusUpdateDto;
import com.qiheng.erp.system.domain.dto.UserPasswordUpdateDto;
import com.qiheng.erp.system.domain.dto.UserRoleDto;
import com.qiheng.erp.system.domain.entity.SysUser;
import com.qiheng.erp.system.domain.vo.SysUserVo;
import com.qiheng.erp.system.service.ISysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

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
    public Result<PageResult<SysUserVo>> page(@Valid SysUserPageDto dto) {
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
    public Result<SysUserVo> getById(@PathVariable Long userId) {
        StpUtil.checkPermission("system:user:query");
        log.info("用户详情查询，参数: {}", userId);
        SysUserVo result = sysUserService.getDetailById(userId);
        return Result.ok(result);
    }

    /**
     * 用户新增
     * @param sysUser 用户实体
     * @return 新增结果
     */
    @PostMapping
    @Operation(summary = "用户新增")
    public Result<SysUserVo> save(@RequestBody @Valid SysUser sysUser) {
        StpUtil.checkPermission("system:user:manage");
        log.info("用户新增，参数: {}", sysUser);
        SysUserVo vo =  sysUserService.saveUser(sysUser);
        return Result.ok(vo);
    }

    /**
     * 批量更新用户状态
     * @param dto 更新状态条件
     * @return 更新结果
     */
    @PatchMapping("/batch/status")
    @Operation(summary = "批量更新用户状态")
    public Result<Void> updateStatus(@RequestBody @Valid SysUserStatusUpdateDto dto) {
        StpUtil.checkPermission("system:user:manage");
        log.info("批量更新用户状态，参数: {}", dto);
        sysUserService.updateStatus(dto);
        return Result.ok();
    }

    /**
     * 更新用户状态
     * @param userId 用户ID
     * @param body 更新状态条件
     * @return 更新结果
     */
    @PatchMapping("/{userId}/status")
    @Operation(summary = "更新用户状态")
    public Result<Void> updateStatus(@PathVariable Long userId, @RequestBody Map<String, Integer> body) {
        StpUtil.checkPermission("system:user:manage");
        Integer status = body.get("status");
        log.info("更新用户状态，参数: {}, {}", userId, status);
        if (status == null) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        sysUserService.updateStatusById(userId, status);
        return Result.ok();
    }

    /**
     * 批量更新用户密码
     * @param dto 更新密码条件
     * @return 更新结果
     */
    @PatchMapping("/batch/password")
    @Operation(summary = "批量更新用户密码")
    public Result<Void> updatePassword(@RequestBody @Valid UserPasswordUpdateDto dto) {
        StpUtil.checkPermission("system:user:manage");
        log.info("批量更新用户密码，参数: {}", dto);
        sysUserService.updatePasswordByIds(dto);
        return Result.ok();
    }

    /**
     * 更新用户密码
     * @param userId 用户ID
     * @param body 更新密码条件
     * @return 更新结果
     */
    @PatchMapping("/{userId}/password")
    @Operation(summary = "更新用户密码")
    public Result<Void> updatePassword(@PathVariable Long userId, @RequestBody Map<String, String> body) {
        StpUtil.checkPermission("system:user:manage");
        String password = body.get("password");
        log.info("更新用户密码，参数: {}, {}", userId, password);
        sysUserService.updatePasswordById(userId, password);
        return Result.ok();
    }

    /**
     * 批量删除用户
     * @param userIds 删除用户ID列表
     * @return 删除结果
     */
    @PostMapping("/batch/delete")
    @Operation(summary = "批量删除用户")
    public Result<Void> deleteBatch(@RequestBody Map<String, List<String>> userIds) {
        StpUtil.checkPermission("system:user:manage");
        List<String> ids = userIds.get("userIds");
        if (ids == null || ids.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        log.info("批量删除用户，参数: {}", ids);
        sysUserService.deleteBatch(ids);
        return Result.ok();
    }

    /**
     * 删除用户
     * @param userId 用户ID
     * @return 删除结果
     */
    @DeleteMapping("/{userId}")
    @Operation(summary = "删除用户")
    public Result<Void> delete(@PathVariable Long userId) {
        StpUtil.checkPermission("system:user:manage");
        log.info("删除用户，参数: {}", userId);
        sysUserService.deleteBatch(List.of(userId.toString()));
        return Result.ok();
    }

    /**
     * 更新用户
     * @param userId 用户ID
     * @param sysUser 用户实体
     * @return 更新结果
     */
    @PutMapping("/{userId}")
    @Operation(summary = "更新用户")
    public Result<SysUserVo> update(@PathVariable Long userId, @RequestBody SysUser sysUser) {
        StpUtil.checkPermission("system:user:manage");
        log.info("更新用户，参数: {}, {}", userId, sysUser);
        SysUserVo vo = sysUserService.updateUser(userId, sysUser);
        return Result.ok(vo);
    }

    /**
     * 更新用户角色
     * @param userId 用户ID
     * @param roleIds 更新角色ID列表
     * @return 更新结果
     */
    @PutMapping("/{userId}/roles")
    @Operation(summary = "更新用户角色")
    public Result<Void> updateRoles(@PathVariable Long userId, @RequestBody Map<String, List<String>> roleIds) {
        StpUtil.checkPermission("system:user:manage");
        List<String> ids = roleIds.get("roleIds");
        if (ids == null || ids.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        log.info("更新用户角色，参数: {}, {}", userId, ids);
        sysUserService.updateRoles(userId, ids);
        return Result.ok();
    }
}