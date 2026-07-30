package com.qiheng.erp.purchase.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.purchase.domain.purchaseorder.dto.PurchaseOrderPageDto;
import com.qiheng.erp.purchase.domain.purchaseorder.vo.PurchaseOrderVo;
import com.qiheng.erp.purchase.service.IPurchaseOrderService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
