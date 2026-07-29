package com.qiheng.erp.purchase.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.purchase.domain.dto.SupplierBatchDeleteDto;
import com.qiheng.erp.purchase.domain.dto.SupplierBatchStatusDto;
import com.qiheng.erp.purchase.domain.dto.SupplierCreateDto;
import com.qiheng.erp.purchase.domain.dto.SupplierPageDto;
import com.qiheng.erp.purchase.domain.dto.SupplierStatusDto;
import com.qiheng.erp.purchase.domain.dto.SupplierUpdateDto;
import com.qiheng.erp.purchase.domain.vo.SupplierVo;
import com.qiheng.erp.purchase.service.ISupplierService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
/**
 * <p>
 * 供应商表 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
@RestController
@RequestMapping("/purchase/suppliers")
@Slf4j
public class SupplierController {

    @Autowired
    private ISupplierService supplierService;

    /**
     * 供应商分页查询
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    @GetMapping
    @Operation(summary = "供应商分页查询")
    public Result<PageResult<SupplierVo>> page(@Valid SupplierPageDto dto) {
        StpUtil.checkPermission("supplier:query");
        log.info("供应商分页查询，参数: {}", dto);
        PageResult<SupplierVo> page = supplierService.page(dto);
        return Result.ok(page);
    }

    /**
     * 新增供应商
     * @param dto 新增供应商请求DTO
     * @return 供应商VO
     */
    @PostMapping
    @Operation(summary = "新增供应商")
    public Result<SupplierVo> create(@Valid @RequestBody SupplierCreateDto dto) {
        StpUtil.checkPermission("purchase:create");
        log.info("新增供应商，参数: {}", dto);
        SupplierVo vo = supplierService.create(dto);
        return Result.ok(vo);
    }

    /**
     * 编辑供应商
     * @param supplierId 供应商ID
     * @param dto 编辑供应商请求DTO
     * @return 供应商VO
     */
    @PutMapping("/{supplierId}")
    @Operation(summary = "编辑供应商")
    public Result<SupplierVo> update(@PathVariable Long supplierId, @Valid @RequestBody SupplierUpdateDto dto) {
        StpUtil.checkPermission("purchase:create");
        log.info("编辑供应商，参数: supplierId={}, dto={}", supplierId, dto);
        SupplierVo vo = supplierService.update(supplierId, dto);
        return Result.ok(vo);
    }

    /**
     * 批量修改供应商状态
     * @param dto 批量状态更新请求DTO
     * @return 无
     */
    @PatchMapping("/batch/status")
    @Operation(summary = "批量修改供应商状态")
    public Result<Void> batchUpdateStatus(@Valid @RequestBody SupplierBatchStatusDto dto) {
        StpUtil.checkPermission("purchase:create");
        log.info("批量修改供应商状态，参数: {}", dto);
        supplierService.batchUpdateStatus(dto);
        return Result.ok();
    }

    /**
     * 修改供应商状态，复用批量接口
     * @param supplierId 供应商ID
     * @param dto 状态更新请求DTO
     * @return 无
     */
    @PatchMapping("/{supplierId}/status")
    @Operation(summary = "修改供应商状态")
    public Result<Void> updateStatus(@PathVariable Long supplierId, @Valid @RequestBody SupplierStatusDto dto) {
        StpUtil.checkPermission("purchase:create");
        log.info("修改供应商状态，参数: {}, {}", supplierId, dto);
        SupplierBatchStatusDto batchDto = new SupplierBatchStatusDto();
        batchDto.setSupplierIds(List.of(String.valueOf(supplierId)));
        batchDto.setVersionBySupplierId(Map.of(String.valueOf(supplierId), dto.getVersion()));
        batchDto.setStatus(dto.getStatus());
        supplierService.batchUpdateStatus(batchDto);
        return Result.ok();
    }

    /**
     * 批量删除供应商
     * @param dto 批量删除请求DTO
     * @return 有失败返回 fail（含失败详情），全部成功返回 ok
     */
    @PostMapping("/batch/delete")
    @Operation(summary = "批量删除供应商")
    public Result<Void> batchDelete(@Valid @RequestBody SupplierBatchDeleteDto dto) {
        StpUtil.checkPermission("purchase:create");
        log.info("批量删除供应商，参数: {}", dto);
        Map<String, String> failures = supplierService.batchDelete(dto);
        if (failures.isEmpty()) {
            return Result.ok();
        }
        log.warn("批量删除供应商部分失败: {}", failures);
        return Result.fail(ErrorCode.OPERATION_FAILED.getCode(),
                "部分供应商删除失败: " + failures);
    }

    /**
     * 删除供应商，复用批量接口
     * @param supplierId 供应商ID
     * @param body 请求体，包含 version 字段
     * @return 有失败返回 fail，全部成功返回 ok
     */
    @DeleteMapping("/{supplierId}")
    @Operation(summary = "删除供应商")
    public Result<Void> delete(@PathVariable Long supplierId, @RequestBody Map<String, Object> body) {
        StpUtil.checkPermission("purchase:create");
        Integer version = (Integer) body.get("version");
        if (version == null) {
            return Result.fail(ErrorCode.PARAM_ERROR.getCode(), "版本号不能为空");
        }
        log.info("删除供应商，参数: supplierId={}, version={}", supplierId, version);
        SupplierBatchDeleteDto batchDto = new SupplierBatchDeleteDto();
        batchDto.setSupplierIds(List.of(String.valueOf(supplierId)));
        batchDto.setVersionBySupplierId(Map.of(String.valueOf(supplierId), version));
        Map<String, String> failures = supplierService.batchDelete(batchDto);
        if (failures.isEmpty()) {
            return Result.ok();
        }
        log.warn("删除供应商失败: {}", failures);
        return Result.fail(ErrorCode.OPERATION_FAILED.getCode(),
                "供应商删除失败: " + failures);
    }
}
