package com.qiheng.erp.warehouse.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.warehouse.domain.warehousestock.dto.WarehouseStockPageDto;
import com.qiheng.erp.warehouse.domain.warehousestock.vo.WarehouseStockPageVo;
import com.qiheng.erp.warehouse.service.IWarehouseStockService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 库存余额表 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-07-17
 */
@RestController
@RequestMapping("/warehouse/stocks")
@Slf4j
public class WarehouseStockController {
    @Autowired
    private IWarehouseStockService warehouseStockService;

    /**
     * 分页查询库存余额
     * @param dto 分页查询请求
     * @return 库存余额分页结果
     */
    @GetMapping
    public Result<WarehouseStockPageVo> page(@Valid WarehouseStockPageDto dto) {
        StpUtil.checkPermission("warehouse:query");
        log.info("分页查询库存余额，参数: {}", dto);
        return Result.ok(warehouseStockService.page(dto));
    }
}
