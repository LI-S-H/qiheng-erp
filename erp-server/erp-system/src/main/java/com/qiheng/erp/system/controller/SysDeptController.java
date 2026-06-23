package com.qiheng.erp.system.controller;


import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.bean.BeanUtil;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.system.domain.dto.BatchDeleteDto;
import com.qiheng.erp.system.domain.dto.BatchStatusUpdateDto;
import com.qiheng.erp.system.domain.dto.StatusUpdateDto;
import com.qiheng.erp.system.domain.dto.SysDeptDto;
import com.qiheng.erp.system.domain.entity.SysDept;
import com.qiheng.erp.system.service.ISysDeptService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * <p>
 * 部门表 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-06-17
 */
@RestController
@RequestMapping("/system/depts")
@Slf4j
public class SysDeptController {

    @Autowired
    private ISysDeptService sysDeptService;


    /**
     * 查询部门列表，附带用户数量统计（LEFT JOIN + GROUP BY）
     *
     * @param deptName 部门名称筛选
     * @param status   状态筛选
     * @return 部门列表（含 userCount）
     */
    @GetMapping
    public Result<List<SysDeptDto>> list(
            @RequestParam(required = false) String deptName,
            @RequestParam(required = false) Integer status)
    {
        log.info("查询部门列表，deptName: {}, status: {}", deptName, status);
        List<SysDeptDto> list = sysDeptService.listWithUserCount(deptName, status);
        return Result.ok(list);
    }

    /**
     * 保存部门
     * @param dept 部门信息
     * @return 保存后的部门信息（含 deptId）
     */
    @PostMapping
    public Result<SysDeptDto> save(@RequestBody SysDept dept) {
        log.info("保存部门: {}", dept);
        StpUtil.checkPermission("system:dept:manage");
        SysDeptDto dto = sysDeptService.saveDept(dept);
        return Result.ok(dto);
    }

    /**
     * 修改部门状态
     * @param deptId
     * @param request 状态修改请求
     * @return
     */
    @PatchMapping("/{deptId}/status")
    public Result<Void> updateStatus(
            @PathVariable Long deptId,
            @Valid @RequestBody StatusUpdateDto request)
    {
        StpUtil.checkPermission("system:dept:manage");
        log.info("修改部门状态，deptId: {}, status: {}", deptId, request.getStatus());
        sysDeptService.updateStatus(deptId, request.getStatus());
        return Result.ok();
    }

    /**
     * 批量修改部门状态
     * @param requests 批量状态修改请求
     * @return
     */
    @PatchMapping("/batch/status")
    public Result<Void> batchUpdateStatus(
        @Valid @RequestBody BatchStatusUpdateDto requests)
    {
        StpUtil.checkPermission("system:dept:manage");
        log.info("批量修改部门状态，requests: {}", requests);
        sysDeptService.batchUpdateStatus(requests.getDeptIds(), requests.getStatus());
        return Result.ok();
    }

    /**
     * 批量删除部门
     * @param
     * @return
     */
    @PostMapping("/batch/delete")
    public Result<Void> batchDelete(@Valid @RequestBody BatchDeleteDto request){
        StpUtil.checkPermission("system:dept:manage");
        log.info("批量删除部门，deptIds: {}", request.getDeptIds());
        sysDeptService.batchDelete(request.getDeptIds());
        return Result.ok();
    }

    /**
     * 删除部门
     * @param deptId 部门ID
     * @return
     */
    @DeleteMapping("/{deptId}")
    public Result<Void> delete(@PathVariable Long deptId) {
        StpUtil.checkPermission("system:dept:manage");
        log.info("删除部门，deptId: {}", deptId);
        sysDeptService.delete(deptId);
        return Result.ok();
    }

    /**
     * 查询部门详情
     * @param deptId 部门ID
     * @return 部门详情
     */
    @GetMapping("/{deptId}")
    public Result<SysDeptDto> get(@PathVariable Long deptId) {
        if (deptId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        log.info("查询部门详情，deptId: {}", deptId);
        SysDeptDto dto = sysDeptService.getDetails(deptId);
        return Result.ok(dto);
    }

    /**
     * 更新部门信息
     * @param deptId 部门ID
     * @param request 部门信息
     * @return
     */
    @PutMapping("/{deptId}")
    public Result<SysDeptDto> update(
            @PathVariable Long deptId,
            @Valid @RequestBody SysDeptDto request)
    {
        StpUtil.checkPermission("system:dept:manage");
        log.info("更新部门信息，deptId: {}, request: {}", deptId, request);
        SysDeptDto dto = sysDeptService.updateDept(deptId, request);
        return Result.ok(dto);
    }
}