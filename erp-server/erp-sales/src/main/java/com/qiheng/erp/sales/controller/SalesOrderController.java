package com.qiheng.erp.sales.controller;


import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.dto.OptimisticLockVersionDto;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.sales.domain.salesorder.dto.SalesOrderCreateDto;
import com.qiheng.erp.sales.domain.salesorder.dto.SalesOrderPageDto;
import com.qiheng.erp.sales.domain.salesorder.dto.SalesOrderUpdateDto;
import com.qiheng.erp.sales.domain.salesorder.vo.SalesOrderDetailVo;
import com.qiheng.erp.sales.domain.salesorder.vo.SalesOrderVo;
import com.qiheng.erp.sales.service.ISalesOrderService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /**
     * 新增销售订单草稿（不锁定库存、不生成出库单；销售单号、客户/仓库/产品快照、订单总金额、创建人和审计字段由后端维护）
     * @param dto 草稿新增请求 DTO
     * @return 新增后的销售订单详情VO
     */
    @PostMapping
    @Operation(summary = "新增销售订单草稿")
    public Result<SalesOrderDetailVo> createDraft(@Valid @RequestBody SalesOrderCreateDto dto) {
        StpUtil.checkPermission("sales:create");
        log.info("新增销售订单草稿，参数: {}", dto);
        SalesOrderDetailVo detail = salesOrderService.createDraft(dto);
        return Result.ok(detail);
    }

    /**
     * 获取销售订单详情（主表 + 明细数组）
     * @param salesOrderId 销售订单ID
     * @return 销售订单详情VO
     */
    @GetMapping("/{salesOrderId}")
    @Operation(summary = "获取销售订单详情")
    public Result<SalesOrderDetailVo> getDetail(@PathVariable Long salesOrderId) {
        StpUtil.checkPermission("sales:query");
        log.info("获取销售订单详情，参数: salesOrderId={}", salesOrderId);
        SalesOrderDetailVo detail = salesOrderService.getDetail(salesOrderId);
        return Result.ok(detail);
    }

    /**
     * 提交销售订单（DRAFT → SUBMITTED，数据库行锁锁定可用库存）
     * @param salesOrderId 销售订单ID
     * @param dto 乐观锁版本号请求
     * @return 空结果
     */
    @PostMapping("/{salesOrderId}/submit")
    @Operation(summary = "提交销售订单")
    public Result<Void> submit(@PathVariable Long salesOrderId,
                               @Valid @RequestBody OptimisticLockVersionDto dto) {
        StpUtil.checkPermission("sales:manage");
        log.info("提交销售订单，参数: salesOrderId={}, version={}", salesOrderId, dto.getVersion());
        salesOrderService.submit(salesOrderId, dto.getVersion());
        return Result.ok();
    }

    /**
     * 审核销售订单（SUBMITTED → APPROVED，同一事务内生成 SALES_OUT 待确认出库单）
     * @param salesOrderId 销售订单ID
     * @param dto 乐观锁版本号请求
     * @return 空结果
     */
    @PostMapping("/{salesOrderId}/approve")
    @Operation(summary = "审核销售订单")
    public Result<Void> approve(@PathVariable Long salesOrderId,
                                @Valid @RequestBody OptimisticLockVersionDto dto) {
        StpUtil.checkPermission("sales:manage");
        log.info("审核销售订单，参数: salesOrderId={}, version={}", salesOrderId, dto.getVersion());
        salesOrderService.approve(salesOrderId, dto.getVersion());
        return Result.ok();
    }

    /**
     * 取消销售订单（DRAFT / SUBMITTED → CANCELLED；SUBMITTED 需同一事务内释放锁定库存）
     * @param salesOrderId 销售订单ID
     * @param dto 乐观锁版本号请求
     * @return 空结果
     */
    @PostMapping("/{salesOrderId}/cancel")
    @Operation(summary = "取消销售订单")
    public Result<Void> cancel(@PathVariable Long salesOrderId,
                               @Valid @RequestBody OptimisticLockVersionDto dto) {
        StpUtil.checkPermission("sales:manage");
        log.info("取消销售订单，参数: salesOrderId={}, version={}", salesOrderId, dto.getVersion());
        salesOrderService.cancel(salesOrderId, dto.getVersion());
        return Result.ok();
    }

    /**
     * 编辑销售订单（DRAFT / SUBMITTED 可编辑；SUBMITTED 含库存精确回算）
     * @param salesOrderId 销售订单ID
     * @param dto 编辑请求 DTO
     * @return 编辑后的销售订单详情VO
     */
    @PutMapping("/{salesOrderId}")
    @Operation(summary = "编辑销售订单")
    public Result<SalesOrderDetailVo> update(@PathVariable Long salesOrderId,
                                            @Valid @RequestBody SalesOrderUpdateDto dto) {
        StpUtil.checkPermission("sales:manage");
        log.info("编辑销售订单，参数: salesOrderId={}, dto={}", salesOrderId, dto);
        SalesOrderDetailVo detail = salesOrderService.update(salesOrderId, dto);
        return Result.ok(detail);
    }

}