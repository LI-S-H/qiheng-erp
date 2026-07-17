package com.qiheng.erp.warehouse.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.warehouse.domain.dto.WarehousePageDto;
import com.qiheng.erp.warehouse.domain.vo.WarehouseVo;
import com.qiheng.erp.warehouse.service.IWarehouseService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}