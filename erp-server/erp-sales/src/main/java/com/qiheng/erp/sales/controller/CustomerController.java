package com.qiheng.erp.sales.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.sales.domain.customer.dto.CustomerBatchDeleteDto;
import com.qiheng.erp.sales.domain.customer.dto.CustomerBatchStatusDto;
import com.qiheng.erp.sales.domain.customer.dto.CustomerCreateDto;
import com.qiheng.erp.sales.domain.customer.dto.CustomerPageDto;
import com.qiheng.erp.sales.domain.customer.dto.CustomerStatusDto;
import com.qiheng.erp.sales.domain.customer.dto.CustomerUpdateDto;
import com.qiheng.erp.sales.domain.customer.vo.CustomerVo;
import com.qiheng.erp.sales.service.ICustomerService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 客户表 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
@RestController
@RequestMapping("/sales/customers")
@Slf4j
public class CustomerController {

    @Autowired
    private ICustomerService customerService;

    /**
     * 客户分页查询
     * @param dto 分页查询参数
     * @return 分页结果集
     */
    @GetMapping
    @Operation(summary = "分页查询客户")
    public Result<PageResult<CustomerVo>> page(@Valid CustomerPageDto dto) {
        StpUtil.checkPermission("customer:query");
        log.info("分页查询客户，参数: {}", dto);
        PageResult<CustomerVo> page = customerService.page(dto);
        return Result.ok(page);
    }

    /**
     * 新增客户（后端生成客户编码）
     * @param dto 新增客户请求 DTO
     * @return 新增后的客户 VO
     */
    @PostMapping
    @Operation(summary = "新增客户")
    public Result<CustomerVo> create(@Valid @RequestBody CustomerCreateDto dto) {
        StpUtil.checkPermission("customer:create");
        log.info("新增客户，参数: {}", dto);
        CustomerVo vo = customerService.create(dto);
        return Result.ok(vo);
    }

    /**
     * 编辑客户（乐观锁；客户名称变更时同步 DRAFT 销售订单的客户快照）
     * @param customerId 客户 ID
     * @param dto 编辑客户请求 DTO
     * @return 编辑后的客户 VO
     */
    @PutMapping("/{customerId}")
    @Operation(summary = "编辑客户")
    public Result<CustomerVo> update(@PathVariable Long customerId, @Valid @RequestBody CustomerUpdateDto dto) {
        StpUtil.checkPermission("customer:create");
        log.info("编辑客户，参数: customerId={}, dto={}", customerId, dto);
        CustomerVo vo = customerService.update(customerId, dto);
        return Result.ok(vo);
    }

    /**
     * 批量修改客户状态
     * @param dto 批量状态更新请求 DTO
     * @return 有失败返回 fail（含失败详情），全部成功返回 ok
     */
    @PatchMapping("/batch/status")
    @Operation(summary = "批量修改客户状态")
    public Result<Void> batchUpdateStatus(@Valid @RequestBody CustomerBatchStatusDto dto) {
        StpUtil.checkPermission("customer:manage");
        log.info("批量修改客户状态，参数: {}", dto);
        Map<String, String> failures = customerService.batchUpdateStatus(dto);
        if (failures.isEmpty()) {
            return Result.ok();
        }
        log.warn("批量修改客户状态部分失败: {}", failures);
        return Result.fail(ErrorCode.OPERATION_FAILED.getCode(),
                "部分客户状态更新失败: " + failures);
    }

    /**
     * 修改客户状态，复用批量接口
     * @param customerId 客户 ID
     * @param dto 状态更新请求 DTO
     * @return 有失败抛业务异常，全部成功返回 ok
     */
    @PatchMapping("/{customerId}/status")
    @Operation(summary = "修改客户状态")
    public Result<Void> updateStatus(@PathVariable Long customerId, @Valid @RequestBody CustomerStatusDto dto) {
        StpUtil.checkPermission("customer:manage");
        log.info("修改客户状态，参数: customerId={}, dto={}", customerId, dto);
        CustomerBatchStatusDto batchDto = new CustomerBatchStatusDto();
        batchDto.setCustomerIds(List.of(String.valueOf(customerId)));
        batchDto.setVersionByCustomerId(Map.of(String.valueOf(customerId), dto.getVersion()));
        batchDto.setStatus(dto.getStatus());
        Map<String, String> failures = customerService.batchUpdateStatus(batchDto);
        if (!failures.isEmpty()) {
            String firstFailure = failures.values().iterator().next();
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "客户状态更新失败: " + firstFailure);
        }
        return Result.ok();
    }

    /**
     * 批量逻辑删除客户
     * @param dto 批量删除请求 DTO
     * @return 有失败返回 fail（含失败详情），全部成功返回 ok
     */
    @PostMapping("/batch/delete")
    @Operation(summary = "批量删除客户")
    public Result<Void> batchDelete(@Valid @RequestBody CustomerBatchDeleteDto dto) {
        StpUtil.checkPermission("customer:manage");
        log.info("批量删除客户，参数: {}", dto);
        Map<String, String> failures = customerService.batchDelete(dto);
        if (failures.isEmpty()) {
            return Result.ok();
        }
        log.warn("批量删除客户部分失败: {}", failures);
        return Result.fail(ErrorCode.OPERATION_FAILED.getCode(),
                "部分客户删除失败: " + failures);
    }

    /**
     * 删除客户，复用批量接口
     * @param customerId 客户 ID
     * @param body 请求体，包含 version 字段
     * @return 有失败抛业务异常，全部成功返回 ok
     */
    @DeleteMapping("/{customerId}")
    @Operation(summary = "删除客户")
    public Result<Void> delete(@PathVariable Long customerId, @Valid @RequestBody Map<String, Object> body) {
        StpUtil.checkPermission("customer:manage");
        Integer version = (Integer) body.get("version");
        if (version == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "版本号不能为空");
        }
        log.info("删除客户，参数: customerId={}, version={}", customerId, version);
        CustomerBatchDeleteDto batchDto = new CustomerBatchDeleteDto();
        batchDto.setCustomerIds(List.of(String.valueOf(customerId)));
        batchDto.setVersionByCustomerId(Map.of(String.valueOf(customerId), version));
        Map<String, String> failures = customerService.batchDelete(batchDto);
        if (!failures.isEmpty()) {
            String firstFailure = failures.values().iterator().next();
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(),
                    "客户删除失败: " + firstFailure);
        }
        return Result.ok();
    }
}
