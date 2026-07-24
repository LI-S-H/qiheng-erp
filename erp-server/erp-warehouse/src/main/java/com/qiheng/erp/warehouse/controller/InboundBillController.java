package com.qiheng.erp.warehouse.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.warehouse.domain.dto.InboundBillPageDto;
import com.qiheng.erp.warehouse.domain.vo.InboundBillDetailVo;
import com.qiheng.erp.warehouse.domain.vo.InboundBillPageVo;
import com.qiheng.erp.warehouse.service.IInboundBillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 入库单主表 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-07-23
 */
@RestController
@RequestMapping("/warehouse/inbound-bills")
@Slf4j
public class InboundBillController {

    @Autowired
    private IInboundBillService inboundBillService;

    /**
     * 分页查询入库单记录
     * @param dto 分页查询请求
     * @return 入库单分页结果
     */
    @GetMapping
    @Operation(summary = "分页查询入库单记录")
    public Result<InboundBillPageVo> page(@Valid InboundBillPageDto dto) {
        StpUtil.checkPermission("warehouse:query");
        log.info("分页查询入库单记录，参数: {}", dto);
        return Result.ok(inboundBillService.page(dto));
    }

    /**
     * 根据ID查询入库单详情
     * @param inboundBillId 入库单ID（字符串形式）
     * @return 入库单详情
     */
    @GetMapping("/{inboundBillId}")
    @Operation(summary = "获取入库单详情")
    public Result<InboundBillDetailVo> getDetailById(
            @Parameter(description = "入库单ID，对应 inbound_bill.id", required = true)
            @PathVariable String inboundBillId) {
        StpUtil.checkPermission("warehouse:query");
        log.info("根据ID查询入库单详情，参数: {}", inboundBillId);
        return Result.ok(inboundBillService.getDetailById(inboundBillId));
    }
}