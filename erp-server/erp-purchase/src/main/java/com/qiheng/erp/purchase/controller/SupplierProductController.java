package com.qiheng.erp.purchase.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.purchase.domain.dto.SupplierProductBatchDeleteDto;
import com.qiheng.erp.purchase.domain.dto.SupplierProductBatchStatusDto;
import com.qiheng.erp.purchase.domain.dto.SupplierProductCreateDto;
import com.qiheng.erp.purchase.domain.dto.SupplierProductPageDto;
import com.qiheng.erp.purchase.domain.vo.SupplierProductVo;
import com.qiheng.erp.purchase.service.ISupplierProductService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

/**
 * <p>
 * 供应商供货产品表 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
@RestController
@RequestMapping("/purchase/supplier-products")
@Slf4j
public class SupplierProductController {

    @Autowired
    private ISupplierProductService supplierProductService;

    /**
     * 供货产品分页查询
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    @GetMapping
    @Operation(summary = "供货产品分页查询")
    public Result<PageResult<SupplierProductVo>> page(@Valid SupplierProductPageDto dto) {
        StpUtil.checkPermission("supplier:query");
        log.info("供货产品分页查询，参数: {}", dto);
        PageResult<SupplierProductVo> page = supplierProductService.page(dto);
        return Result.ok(page);
    }

    /**
     * 新增供货产品
     * @param dto 新增供货产品请求DTO
     * @return 供货产品VO
     */
    @PostMapping
    @Operation(summary = "新增供货产品")
    public Result<SupplierProductVo> create(@Valid @RequestBody SupplierProductCreateDto dto) {
        StpUtil.checkPermission("purchase:create");
        log.info("新增供货产品，参数: {}", dto);
        SupplierProductVo vo = supplierProductService.create(dto);
        return Result.ok(vo);
    }

    /**
     * 编辑供货产品
     * @param supplierProductId 供货产品ID
     * @param dto 编辑供货产品请求DTO
     * @return 供货产品VO
     */
    @PutMapping("/{supplierProductId}")
    @Operation(summary = "编辑供货产品")
    public Result<SupplierProductVo> update(@PathVariable Long supplierProductId,
                                            @Valid @RequestBody SupplierProductCreateDto dto) {
        StpUtil.checkPermission("purchase:create");
        log.info("编辑供货产品，参数: supplierProductId={}, dto={}", supplierProductId, dto);
        SupplierProductVo vo = supplierProductService.update(supplierProductId, dto);
        return Result.ok(vo);
    }

    /**
     * 批量修改供货产品状态
     * @param dto 批量状态修改请求DTO
     * @return 有失败返回 fail（含失败详情），全部成功返回 ok
     */
    @PatchMapping("/batch/status")
    @Operation(summary = "批量修改供货产品状态")
    public Result<Map<String, String>> batchUpdateStatus(@Valid @RequestBody SupplierProductBatchStatusDto dto) {
        StpUtil.checkPermission("purchase:create");
        log.info("批量修改供货产品状态，参数: {}", dto);
        Map<String, String> failures = supplierProductService.batchUpdateStatus(dto);
        if (failures.isEmpty()) {
            return Result.ok();
        }
        log.warn("批量修改供货产品状态部分失败: {}", failures);
        return Result.fail(ErrorCode.OPERATION_FAILED.getCode(),
                "部分供货产品状态更新失败: " + failures);
    }

    /**
     * 批量删除供货产品（逻辑删除）
     * @param dto 批量删除请求DTO
     * @return 有失败返回 fail（含失败详情），全部成功返回 ok
     */
    @PostMapping("/batch/delete")
    @Operation(summary = "批量删除供货产品")
    public Result<Void> batchDelete(@Valid @RequestBody SupplierProductBatchDeleteDto dto) {
        StpUtil.checkPermission("purchase:create");
        log.info("批量删除供货产品，参数: {}", dto);
        Map<String, String> failures = supplierProductService.batchDelete(dto);
        if (failures.isEmpty()) {
            return Result.ok();
        }
        log.warn("批量删除供货产品部分失败: {}", failures);
        return Result.fail(ErrorCode.OPERATION_FAILED.getCode(),
                "部分供货产品删除失败: " + failures);
    }

    /**
     * 删除供货产品（逻辑删除）
     * @param supplierProductId 供货产品ID
     * @param body 请求体，包含 version 字段
     * @return 操作结果
     */
    @DeleteMapping("/{supplierProductId}")
    @Operation(summary = "删除供货产品")
    public Result<Void> deleteById(@PathVariable Long supplierProductId,
                                   @RequestBody Map<String, Integer> body) {
        StpUtil.checkPermission("purchase:create");
        log.info("删除供货产品，参数: supplierProductId={}, body={}", supplierProductId, body);
        Integer version = body.get("version");
        if (version == null) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "版本号不能为空");
        }
        try {
            if (version < 0) {
                throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "版本号不能为负数");
            }
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "版本号格式错误");
        }
        // 复用批量删除接口
        SupplierProductBatchDeleteDto dto = new SupplierProductBatchDeleteDto();
        dto.setSupplierProductIds(Collections.singletonList(supplierProductId.toString()));
        dto.setVersionBySupplierProductId(Collections.singletonMap(supplierProductId.toString(), version));
        Map<String, String> failures = supplierProductService.batchDelete(dto);
        if (failures.isEmpty()) {
            return Result.ok();
        }
        log.warn("删除供货产品失败: {}", failures);
        return Result.fail(ErrorCode.OPERATION_FAILED.getCode(),
                "供货产品删除失败: " + failures);
    }
}
