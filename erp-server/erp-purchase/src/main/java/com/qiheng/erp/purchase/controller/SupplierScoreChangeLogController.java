package com.qiheng.erp.purchase.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.purchase.domain.supplierscore.dto.SupplierScoreChangeLogPageDto;
import com.qiheng.erp.purchase.domain.supplierscore.vo.SupplierScoreChangeLogVo;
import com.qiheng.erp.purchase.service.ISupplierScoreChangeLogService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 供应商与供货产品共用的评分变更记录查询入口。 */
@RestController
@RequestMapping("/purchase/score-change-logs")
@RequiredArgsConstructor
public class SupplierScoreChangeLogController {

    private final ISupplierScoreChangeLogService scoreChangeLogService;

    @GetMapping
    @Operation(summary = "分页查询评分变更记录")
    public Result<PageResult<SupplierScoreChangeLogVo>> page(@Valid SupplierScoreChangeLogPageDto dto) {
        StpUtil.checkPermission("supplier:query");
        return Result.ok(scoreChangeLogService.pageScoreChangeLogs(dto));
    }
}
