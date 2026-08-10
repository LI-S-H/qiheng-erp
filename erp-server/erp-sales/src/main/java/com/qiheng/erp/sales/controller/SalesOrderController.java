package com.qiheng.erp.sales.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.sales.domain.salesorder.dto.SalesOrderPageDto;
import com.qiheng.erp.sales.domain.salesorder.vo.SalesOrderVo;
import com.qiheng.erp.sales.service.ISalesOrderService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 销售订单主表 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
@RestController
@RequestMapping("/sales/orders")
@Slf4j
public class SalesOrderController {

    @Autowired
    private ISalesOrderService salesOrderService;

    /**
     * 销售订单分页查询（列表不返回明细数组）
     * @param dto 分页查询参数
     * @return 分页结果集
     */
    @GetMapping
    @Operation(summary = "分页查询销售订单")
    public Result<PageResult<SalesOrderVo>> page(@Valid SalesOrderPageDto dto) {
        StpUtil.checkPermission("sales:query");
        log.info("分页查询销售订单，参数: {}", dto);
        PageResult<SalesOrderVo> page = salesOrderService.page(dto);
        return Result.ok(page);
    }

}