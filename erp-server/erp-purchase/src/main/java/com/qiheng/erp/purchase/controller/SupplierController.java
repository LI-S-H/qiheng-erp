package com.qiheng.erp.purchase.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierBatchDeleteDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierBatchStatusDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierCreateDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierDeleteDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierPageDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierStatusDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierUpdateDto;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierServiceScoreDto;
import com.qiheng.erp.purchase.domain.supplierscore.dto.SupplierScoreChangeLogPageDto;
import com.qiheng.erp.purchase.domain.supplier.vo.SupplierBatchFailure;
import com.qiheng.erp.purchase.domain.supplier.vo.SupplierVo;
import com.qiheng.erp.purchase.domain.supplierscore.vo.SupplierScoreChangeLogVo;
import com.qiheng.erp.purchase.service.ISupplierScoreChangeLogService;
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

    @Autowired
    private ISupplierScoreChangeLogService supplierScoreChangeLogService;

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

    @GetMapping("/{supplierId}")
    @Operation(summary = "获取供应商详情")
    public Result<SupplierVo> detail(@PathVariable Long supplierId) {
        StpUtil.checkPermission("supplier:query");
        return Result.ok(supplierService.detail(supplierId));
    }

    @GetMapping("/{supplierId}/score-change-logs")
    @Operation(summary = "分页查询供应商评分变更记录")
    public Result<PageResult<SupplierScoreChangeLogVo>> pageScoreChangeLogs(@PathVariable Long supplierId,
                                                                              @Valid SupplierScoreChangeLogPageDto dto) {
        StpUtil.checkPermission("supplier:query");
        return Result.ok(supplierScoreChangeLogService.pageScoreChangeLogsBySupplier(supplierId, dto));
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

    @PutMapping("/{supplierId}/service-score")
    @Operation(summary = "调整供应商服务分")
    public Result<SupplierVo> updateServiceScore(@PathVariable Long supplierId,
                                                  @Valid @RequestBody SupplierServiceScoreDto dto) {
        StpUtil.checkPermission("supplier:manage");
        return Result.ok(supplierService.updateServiceScore(supplierId, dto));
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
     * <p>校验类失败(供货产品、采购订单、采购退货单)整批回滚 409;乐观锁/版本号类
     * 失败单条跳过,作为业务级正常响应(data=SupplierBatchFailure[])。空数组=全部成功,
     * 非空数组=部分失败,前端按 data 渲染失败明细。</p>
     * @param dto 批量删除请求DTO
     * @return 失败明细;空数组表示全部成功
     */
    @PostMapping("/batch/delete")
    @Operation(summary = "批量删除供应商")
    public Result<List<SupplierBatchFailure>> batchDelete(@Valid @RequestBody SupplierBatchDeleteDto dto) {
        StpUtil.checkPermission("purchase:create");
        log.info("批量删除供应商，参数: {}", dto);
        List<SupplierBatchFailure> failures = supplierService.batchDelete(dto);
        if (!failures.isEmpty()) {
            log.warn("批量删除供应商部分失败: {}", failures);
        }
        return Result.ok(failures);
    }

    /**
     * 删除供应商，复用批量接口
     * @param supplierId 供应商ID
     * @param dto 请求体，包含 version 字段
     * @return 失败明细;空数组表示成功
     */
    @DeleteMapping("/{supplierId}")
    @Operation(summary = "删除供应商")
    public Result<List<SupplierBatchFailure>> delete(@PathVariable Long supplierId,
                                                     @Valid @RequestBody SupplierDeleteDto dto) {
        StpUtil.checkPermission("purchase:create");
        log.info("删除供应商，参数: supplierId={}, version={}", supplierId, dto.getVersion());
        SupplierBatchDeleteDto batchDto = new SupplierBatchDeleteDto();
        batchDto.setSupplierIds(List.of(String.valueOf(supplierId)));
        batchDto.setVersionBySupplierId(Map.of(String.valueOf(supplierId), dto.getVersion()));
        List<SupplierBatchFailure> failures = supplierService.batchDelete(batchDto);
        if (!failures.isEmpty()) {
            log.warn("删除供应商失败: {}", failures);
        }
        return Result.ok(failures);
    }
}
