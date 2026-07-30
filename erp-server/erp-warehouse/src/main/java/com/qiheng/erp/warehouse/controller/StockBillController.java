package com.qiheng.erp.warehouse.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.warehouse.domain.stockbill.dto.StockBillPageDto;
import com.qiheng.erp.warehouse.domain.stockbill.vo.StockBillDetailsVo;
import com.qiheng.erp.warehouse.domain.stockbill.vo.StockBillPageVo;
import com.qiheng.erp.warehouse.service.IStockBillItemService;
import com.qiheng.erp.warehouse.service.IStockBillService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 库存流水凭证主表 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-07-19
 */
@RestController
@RequestMapping("/warehouse/stock-bills")
@Slf4j
public class StockBillController {

    @Autowired
    private IStockBillService stockBillService;

    @Autowired
    private IStockBillItemService stockBillItemService;

    /**
     * 分页查询库存流水记录
     * @param dto 分页查询请求
     * @return 库存流水分页结果
     */
    @GetMapping
    @Operation(summary = "分页查询库存流水记录")
    public Result<StockBillPageVo> page(@Valid StockBillPageDto dto) {
        StpUtil.checkPermission("warehouse:query");
        log.info("分页查询库存流水记录，参数: {}", dto);
        return Result.ok(stockBillService.page(dto));
    }

    /**
     * 根据库存流水凭证ID查询库存流水记录
     * @param stockLedgerId 库存流水凭证ID
     * @return 库存流水记录
     */
    @GetMapping("/{stockLedgerId}")
    @Operation(summary = "根据库存流水凭证ID查询库存流水记录")
    public Result<StockBillDetailsVo> getById(@PathVariable Long stockLedgerId) {
        StpUtil.checkPermission("warehouse:query");
        log.info("根据库存流水凭证ID查询库存流水记录，参数: {}", stockLedgerId);
        StockBillDetailsVo detailsVo = stockBillItemService.getDetailsById(stockLedgerId);
        return Result.ok(detailsVo);
    }
}