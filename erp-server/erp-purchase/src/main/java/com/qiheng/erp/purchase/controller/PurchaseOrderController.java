package com.qiheng.erp.purchase.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.dto.OptimisticLockVersionDto;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.purchase.domain.purchaseorder.dto.PurchaseOrderCreateDto;
import com.qiheng.erp.purchase.domain.purchaseorder.dto.PurchaseOrderPageDto;
import com.qiheng.erp.purchase.domain.purchaseorder.dto.PurchaseOrderUpdateDto;
import com.qiheng.erp.purchase.domain.purchaseorder.vo.PurchaseOrderDetailVo;
import com.qiheng.erp.purchase.domain.purchaseorder.vo.PurchaseOrderVo;
import com.qiheng.erp.purchase.service.IPurchaseOrderService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 * 采购订单 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@RestController
@RequestMapping("/purchase/orders")
@Slf4j
public class PurchaseOrderController {

    @Autowired
    private IPurchaseOrderService purchaseOrderService;

    /**
     * 分页查询采购订单
     * @param dto 分页查询参数DTO
     * @return 分页查询结果VO
     */
    @GetMapping
    @Operation(summary = "分页查询采购订单")
    public Result<PageResult<PurchaseOrderVo>> page(@Valid PurchaseOrderPageDto dto) {
        StpUtil.checkPermission("purchase:query");
        log.info("分页查询采购订单，参数: {}", dto);
        PageResult<PurchaseOrderVo> page = purchaseOrderService.page(dto);
        return Result.ok(page);
    }

    /**
     * 获取采购订单详情
     * @param purchaseOrderId 采购订单ID
     * @return 采购订单详情VO（主表 + 明细列表）
     */
    @GetMapping("/{purchaseOrderId}")
    @Operation(summary = "获取采购订单详情")
    public Result<PurchaseOrderDetailVo> getDetail(@PathVariable Long purchaseOrderId) {
        StpUtil.checkPermission("purchase:query");
        log.info("获取采购订单详情，参数: {}", purchaseOrderId);
        PurchaseOrderDetailVo detail = purchaseOrderService.getDetail(purchaseOrderId);
        return Result.ok(detail);
    }

    /**
     * 新增采购订单草稿
     * @param dto 新增采购订单请求DTO
     * @return 采购订单详情VO
     */
    @PostMapping
    @Operation(summary = "新增采购订单草稿")
    public Result<PurchaseOrderDetailVo> createDraft(@Valid @RequestBody PurchaseOrderCreateDto dto) {
        StpUtil.checkPermission("purchase:create");
        log.info("新增采购订单草稿，参数: {}", dto);
        PurchaseOrderDetailVo detail = purchaseOrderService.createDraft(dto);
        return Result.ok(detail);
    }

    /**
     * 编辑采购订单
     * @param purchaseOrderId 采购订单ID
     * @param dto 编辑采购订单请求DTO
     * @return 采购订单详情VO
     */
    @PutMapping("/{purchaseOrderId}")
    @Operation(summary = "编辑采购订单")
    public Result<PurchaseOrderDetailVo> update(@PathVariable Long purchaseOrderId,
                                                @Valid @RequestBody PurchaseOrderUpdateDto dto) {
        // DRAFT 需要 purchase:create，SUBMITTED 需要 purchase:manage，二者居其一即可访问
        StpUtil.checkPermissionOr("purchase:create", "purchase:manage");
        log.info("编辑采购订单，ID: {}, 参数: {}", purchaseOrderId, dto);
        PurchaseOrderDetailVo detail = purchaseOrderService.update(purchaseOrderId, dto);
        return Result.ok(detail);
    }

    /**
     * 提交采购订单
     * @param purchaseOrderId 采购订单ID
     * @param dto 乐观锁版本号请求
     * @return 空结果
     */
    @PostMapping("/{purchaseOrderId}/submit")
    @Operation(summary = "提交采购订单")
    public Result<Void> submit(@PathVariable Long purchaseOrderId,
                               @Valid @RequestBody OptimisticLockVersionDto dto) {
        StpUtil.checkPermission("purchase:manage");
        log.info("提交采购订单，ID: {}, 版本: {}", purchaseOrderId, dto.getVersion());
        purchaseOrderService.submit(purchaseOrderId, dto.getVersion());
        return Result.ok();
    }

    /**
     * 审核采购订单
     * @param purchaseOrderId 采购订单ID
     * @param dto 乐观锁版本号请求
     * @return 空结果
     */
    @PostMapping("/{purchaseOrderId}/approve")
    @Operation(summary = "审核采购订单")
    public Result<Void> approve(@PathVariable Long purchaseOrderId,
                                @Valid @RequestBody OptimisticLockVersionDto dto) {
        StpUtil.checkPermission("purchase:manage");
        log.info("审核采购订单，ID: {}, 版本: {}", purchaseOrderId, dto.getVersion());
        purchaseOrderService.approve(purchaseOrderId, dto.getVersion());
        return Result.ok();
    }

    /**
     * 取消采购订单
     * @param purchaseOrderId 采购订单ID
     * @param dto 乐观锁版本号请求
     * @return 空结果
     */
    @PostMapping("/{purchaseOrderId}/cancel")
    @Operation(summary = "取消采购订单")
    public Result<Void> cancel(@PathVariable Long purchaseOrderId,
                               @Valid @RequestBody OptimisticLockVersionDto dto) {
        StpUtil.checkPermission("purchase:create");
        log.info("取消采购订单，ID: {}, 版本: {}", purchaseOrderId, dto.getVersion());
        purchaseOrderService.cancel(purchaseOrderId, dto.getVersion());
        return Result.ok();
    }
}
