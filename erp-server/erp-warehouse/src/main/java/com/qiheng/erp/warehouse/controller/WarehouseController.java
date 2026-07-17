package com.qiheng.erp.warehouse.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.warehouse.domain.dto.WarehouseBatchDeleteDto;
import com.qiheng.erp.warehouse.domain.dto.WarehouseBatchStatusDto;
import com.qiheng.erp.warehouse.domain.dto.WarehousePageDto;
import com.qiheng.erp.warehouse.domain.dto.WarehouseStatusDto;
import com.qiheng.erp.warehouse.domain.entity.Warehouse;
import com.qiheng.erp.warehouse.domain.vo.WarehouseVo;
import com.qiheng.erp.warehouse.service.IWarehouseService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

/**
 * <p>
 * 仓库表 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-07-16
 */
@RestController
@RequestMapping("/warehouse/warehouses")
@Slf4j
public class WarehouseController {

    @Autowired
    private IWarehouseService warehouseService;

    /**
     * 分页查询仓库
     * @param dto 分页查询参数
     * @return 分页结果集
     */
    @GetMapping
    @Operation(summary = "分页查询仓库")
    public Result<PageResult<WarehouseVo>> page(@Valid WarehousePageDto dto) {
        StpUtil.checkPermission("warehouse:query");
        log.info("分页查询仓库，参数: {}", dto);
        PageResult<WarehouseVo> page = warehouseService.page(dto);
        return Result.ok(page);
    }

    /**
     * 根据ID查询仓库详情
     * @param warehouseId 仓库ID
     * @return 仓库VO
     */
    @GetMapping("/{warehouseId}")
    @Operation(summary = "根据ID查询仓库详情")
    public Result<WarehouseVo> getDetailById(@PathVariable Long warehouseId) {
        StpUtil.checkPermission("warehouse:query");
        log.info("根据ID查询仓库详情，参数: {}", warehouseId);
        WarehouseVo vo = warehouseService.getDetailById(warehouseId);
        return Result.ok(vo);
    }

    /**
     * 仓库新增
     * @param warehouse 仓库实体
     * @return 仓库VO
     */
    @PostMapping
    @Operation(summary = "仓库新增")
    public Result<WarehouseVo> save(@Valid @RequestBody Warehouse warehouse) {
        StpUtil.checkPermission("warehouse:manage");
        log.info("仓库新增，参数: {}", warehouse);
        WarehouseVo vo = warehouseService.add(warehouse);
        return Result.ok(vo);
    }

    /**
     * 更新仓库状态
     * @param warehouseId 仓库ID
     * @return 无
     */
    @PatchMapping("/{warehouseId}/status")
    @Operation(summary = "更新仓库状态")
    public Result<Void> updateStatus(@PathVariable Long warehouseId,
            @Valid @RequestBody WarehouseStatusDto dto) {
        StpUtil.checkPermission("warehouse:manage");
        log.info("更新仓库状态，参数: warehouseId={}, dto={}", warehouseId, dto);
        warehouseService.updateStatus(warehouseId, dto);
        return Result.ok();
    }


    /**
     * 批量更新仓库状态
     * @param dto 批量更新仓库状态参数DTO
     * @return 有失败返回 fail（含失败详情），全部成功返回 ok
     */
    @PatchMapping("/batch/status")
    @Operation(summary = "批量更新仓库状态")
    public Result<java.util.Map<String, String>> updateBatchStatus(@Valid @RequestBody WarehouseBatchStatusDto dto) {
        StpUtil.checkPermission("warehouse:manage");
        log.info("批量更新仓库状态，参数: {}", dto);
        Map<String, String> failures = warehouseService.updateBatchStatus(dto);
        if (failures.isEmpty()) {
            return Result.ok();
        }
        log.warn("批量更新仓库状态部分失败: {}", failures);
        return Result.fail(com.qiheng.erp.common.exception.ErrorCode.OPERATION_FAILED.getCode(),
                "部分仓库状态更新失败: " + failures);
    }

    /**
     * 批量删除仓库（逻辑删除）
     * @param dto 批量删除参数（含 warehouseIds 和 versionByWarehouseId）
     * @return 有失败返回 fail（含失败详情），全部成功返回 ok
     */
    @PostMapping("/batch/delete")
    @Operation(summary = "批量删除仓库")
    public Result<Void> batchDelete(@Valid @RequestBody WarehouseBatchDeleteDto dto) {
        StpUtil.checkPermission("warehouse:manage");
        log.info("批量删除仓库，参数: {}", dto);
        Map<String, String> failures = warehouseService.batchDelete(dto);
        if (failures.isEmpty()) {
            return Result.ok();
        }
        log.warn("批量删除仓库部分失败: {}", failures);
        return Result.fail(ErrorCode.OPERATION_FAILED.getCode(),
                "部分仓库删除失败: " + failures);
    }

    /**
     * 根据ID删除仓库
     * @param warehouseId 仓库ID
     * @param body 请求体，包含 version 字段
     * @return 无
     */
    @DeleteMapping("/{warehouseId}")
    @Operation(summary = "根据ID删除仓库")
    public Result<Void> deleteById(@PathVariable Long warehouseId,
                                   @RequestBody Map<String, Object> body) {
        StpUtil.checkPermission("warehouse:manage");
        log.info("删除仓库，参数: warehouseId={}, body={}", warehouseId, body);
        String version = body.get("version").toString();
        if (!version.equals("0") && !version.equals("1")) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "版本号错误，请重试");
        }
        // 构建批量删除参数DTO
        WarehouseBatchDeleteDto dto = new WarehouseBatchDeleteDto();
        dto.setWarehouseIds(Collections.singletonList(warehouseId.toString()));
        dto.setVersionByWarehouseId(Collections.singletonMap(warehouseId.toString(), Integer.parseInt(version)));
        Map<String, String> failures = warehouseService.batchDelete(dto);
        if (failures.isEmpty()) {
            return Result.ok();
        }
        log.warn("删除仓库失败: {}", failures);
        return Result.fail(ErrorCode.OPERATION_FAILED.getCode(),
                "仓库删除失败: " + failures);
    }
}