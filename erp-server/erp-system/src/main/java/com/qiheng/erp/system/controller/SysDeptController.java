package com.qiheng.erp.system.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.system.domain.dto.SysDeptBatchDeleteDto;
import com.qiheng.erp.system.domain.dto.SysDeptBatchStatusUpdateDto;
import com.qiheng.erp.system.domain.dto.StatusUpdateDto;
import com.qiheng.erp.system.domain.dto.SysDeptDto;
import com.qiheng.erp.system.domain.entity.SysDept;
import com.qiheng.erp.system.domain.vo.DeptOptionVo;
import com.qiheng.erp.system.service.ISysDeptService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "部门管理")
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
    @Operation(summary = "查询部门列表")
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
    @Operation(summary = "保存部门")
    @PostMapping
    public Result<SysDeptDto> save(@RequestBody SysDept dept) {
        log.info("保存部门: {}", dept);
        StpUtil.checkPermission("system:dept:manage");
        SysDeptDto dto = sysDeptService.saveDept(dept);
        return Result.ok(dto);
    }

    /**
     * 修改部门状态
     * @param deptId 部门ID
     * @param request 状态修改请求
     * @return 更新结果
     */
    @Operation(summary = "修改部门状态")
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
     * @return 更新结果
     */
    @Operation(summary = "批量修改部门状态")
    @PatchMapping("/batch/status")
    public Result<Void> batchUpdateStatus(
        @Valid @RequestBody SysDeptBatchStatusUpdateDto requests)
    {
        StpUtil.checkPermission("system:dept:manage");
        log.info("批量修改部门状态，requests: {}", requests);
        sysDeptService.batchUpdateStatus(requests.getDeptIds(), requests.getStatus());
        return Result.ok();
    }

    /**
     * 批量删除部门
     * @param request 批量删除部门请求
     * @return 更新结果
     */
    @Operation(summary = "批量删除部门")
    @PostMapping("/batch/delete")
    public Result<Void> batchDelete(@Valid @RequestBody SysDeptBatchDeleteDto request){
        StpUtil.checkPermission("system:dept:manage");
        log.info("批量删除部门，deptIds: {}", request.getDeptIds());
        sysDeptService.batchDelete(request.getDeptIds());
        return Result.ok();
    }

    /**
     * 删除部门
     * @param deptId 部门ID
     * @return 更新结果
     */
    @Operation(summary = "删除部门")
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
    @Operation(summary = "查询部门详情")
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
     * @return 更新结果
     */
    @Operation(summary = "更新部门信息")
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

    /**
     * 查询部门下拉选项列表
     * @return 部门下拉选项列表
     */
    @GetMapping("/options")
    @Operation(summary = "查询部门下拉选项")
    public Result<List<DeptOptionVo>> options() {
        log.info("查询部门下拉选项");
        List<DeptOptionVo> voList = sysDeptService.getDeptOptions();
        return Result.ok(voList);
    }
}