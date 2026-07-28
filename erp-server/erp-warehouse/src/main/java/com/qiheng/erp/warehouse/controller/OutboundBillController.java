package com.qiheng.erp.warehouse.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.warehouse.domain.dto.OutboundBillCreateDto;
import com.qiheng.erp.warehouse.domain.dto.OutboundBillPageDto;
import com.qiheng.erp.warehouse.domain.vo.OutboundBillDetailVo;
import com.qiheng.erp.warehouse.domain.vo.OutboundBillPageVo;
import com.qiheng.erp.warehouse.service.IOutboundBillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import org.springframework.web.bind.annotation.RestController;

/**
 * <p>
 * 出库单主表 前端控制器
 * </p>
 *
 * @author Li
 * @since 2026-07-26
 */
@RestController
@RequestMapping("/warehouse/outbound-bills")
@Slf4j
public class OutboundBillController {

    @Autowired
    private IOutboundBillService outboundBillService;

    /**
     * 分页查询出库单记录
     * @param dto 分页查询请求
     * @return 出库单分页结果
     */
    @GetMapping
    @Operation(summary = "分页查询出库单记录")
    public Result<OutboundBillPageVo> page(@Valid OutboundBillPageDto dto) {
        StpUtil.checkPermission("warehouse:query");
        log.info("分页查询出库单记录，参数: {}", dto);
        return Result.ok(outboundBillService.page(dto));
    }

    /**
     * 根据ID查询出库单详情
     * @param outboundBillId 出库单ID（字符串形式）
     * @return 出库单详情
     */
    @GetMapping("/{outboundBillId}")
    @Operation(summary = "获取出库单详情")
    public Result<OutboundBillDetailVo> getDetailById(
            @Parameter(description = "出库单ID，对应 outbound_bill.id", required = true)
            @PathVariable String outboundBillId) {
        StpUtil.checkPermission("warehouse:query");
        log.info("根据ID查询出库单详情，参数: {}", outboundBillId);
        return Result.ok(outboundBillService.getDetailById(outboundBillId));
    }

    /**
     * 新增手工出库单草稿
     * @param dto 创建请求
     * @return 出库单详情
     */
    @PostMapping
    @Operation(summary = "新增手工出库单草稿")
    public Result<OutboundBillDetailVo> createDraft(@Valid @RequestBody OutboundBillCreateDto dto) {
        StpUtil.checkPermission("warehouse:manage");
        log.info("新增手工出库单草稿，参数: {}", dto);
        return Result.ok(outboundBillService.createDraft(dto));
    }
}
