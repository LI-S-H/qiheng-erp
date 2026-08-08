package com.qiheng.erp.returnorder.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.dto.OptimisticLockVersionDto;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.returnorder.domain.dto.ReturnOrderCreateDto;
import com.qiheng.erp.returnorder.domain.dto.ReturnOrderPageDto;
import com.qiheng.erp.returnorder.domain.dto.ReturnOrderUpdateDto;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.domain.vo.ReturnOrderDetailVo;
import com.qiheng.erp.returnorder.domain.vo.ReturnOrderItemVo;
import com.qiheng.erp.returnorder.domain.vo.ReturnOrderVo;
import com.qiheng.erp.returnorder.domain.vo.ReturnableSourceOrderVo;
import com.qiheng.erp.returnorder.service.IReturnOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统一退货单查询入口。
 *
 * <p>退货单使用 {@code returnType} 区分采购退货和销售退货。控制器只负责权限校验、
 * 参数边界校验和响应封装，来源订单聚合由服务层委托各业务模块的 Provider 完成。</p>
 */
@RestController
@RequestMapping("/returns")
@RequiredArgsConstructor
@Slf4j
public class ReturnOrderController {
    private final IReturnOrderService returnOrderService;

    /**
     * 分页查询指定业务方向的退货单。
     *
     * @param dto 查询条件，必须携带退货方向
     * @return 退货单分页数据
     */
    @GetMapping
    @Operation(summary = "分页查询统一退货单")
    public Result<PageResult<ReturnOrderVo>> page(@Valid ReturnOrderPageDto dto) {
        checkQueryPermission(dto.getReturnType());
        log.info("分页查询统一退货单，参数: {}", dto);
        return Result.ok(returnOrderService.page(dto));
    }

    /**
     * 查询退货单详情，并按该退货单实际业务方向校验权限。
     *
     * @param returnOrderId 退货单 ID
     * @return 包含明细和可退数量的退货单详情
     */
    @GetMapping("/{returnOrderId}")
    @Operation(summary = "获取退货单详情")
    public Result<ReturnOrderDetailVo> detail(
            @Parameter(description = "退货单ID，对应 return_order.id", required = true)
            @PathVariable String returnOrderId) {
        log.info("获取退货单详情，参数: {}", returnOrderId);
        ReturnOrderDetailVo detail = returnOrderService.getDetail(IdUtil.parseRequiredLongId(returnOrderId, "退货单ID"));
        checkQueryPermission(ReturnType.valueOf(detail.getReturnType()));
        return Result.ok(detail);
    }

    /**
     * 搜索当前业务方向下可发起退货的来源订单。
     *
     * @param returnType 采购退货或销售退货
     * @param sourceOrderNo 来源订单号模糊条件
     * @param pageSize 最大返回条数
     * @return 至少含有一条可退明细的来源订单
     */
    @GetMapping("/source-orders")
    @Operation(summary = "搜索可作为退货来源的订单")
    public Result<List<ReturnableSourceOrderVo>> sourceOrders(
            @Parameter(description = "退货类型", required = true)
            @RequestParam ReturnType returnType,
            @Parameter(description = "来源订单号，使用包含匹配")
            @RequestParam(required = false) String sourceOrderNo,
            @Parameter(description = "最大返回条数，范围1-50")
            @RequestParam(defaultValue = "10") int pageSize) {
        if (pageSize < 1 || pageSize > 50) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "来源订单查询条数必须在 1 到 50 之间");
        }
        checkQueryPermission(returnType);
        log.info("搜索可作为退货来源的订单，returnType: {}, sourceOrderNo: {}, pageSize: {}", returnType, sourceOrderNo, pageSize);
        return Result.ok(returnOrderService.searchSourceOrders(returnType, sourceOrderNo, pageSize));
    }

    /**
     * 查询某一来源订单中仍有可退数量的明细。
     *
     * @param returnType 退货方向，用于选择来源业务模块
     * @param sourceOrderId 来源订单 ID
     * @return 可退来源明细
     */
    @GetMapping("/source-orders/{sourceOrderId}/items")
    @Operation(summary = "查询来源订单的可退明细")
    public Result<List<ReturnOrderItemVo>> sourceItems(
            @Parameter(description = "退货类型", required = true)
            @RequestParam ReturnType returnType,
            @Parameter(description = "来源订单ID", required = true)
            @PathVariable String sourceOrderId) {
        checkQueryPermission(returnType);
        log.info("查询来源订单的可退明细，returnType: {}, sourceOrderId: {}", returnType, sourceOrderId);
        return Result.ok(returnOrderService.listSourceItems(returnType, IdUtil.parseRequiredLongId(sourceOrderId, "来源订单ID")));
    }

    /**
     * 创建统一退货单草稿。
     *
     * @param dto 创建请求
     * @return 包含明细的退货单详情
     */
    @PostMapping
    @Operation(summary = "创建统一退货单草稿")
    public Result<ReturnOrderDetailVo> create(@Valid @RequestBody ReturnOrderCreateDto dto) {
        checkCreatePermission(dto.getReturnType());
        log.info("创建统一退货单草稿，参数: {}", dto);
        return Result.ok(returnOrderService.createDraft(dto));
    }

    /**
     * 编辑统一退货单草稿（DRAFT/SUBMITTED 可编辑）。
     *
     * @param returnOrderId 退货单ID
     * @param dto 编辑请求
     * @return 包含明细的退货单详情
     */
    @PutMapping("/{returnOrderId}")
    @Operation(summary = "编辑统一退货单草稿")
    public Result<ReturnOrderDetailVo> update(
            @Parameter(description = "退货单ID", required = true)
            @PathVariable String returnOrderId,
            @Valid @RequestBody ReturnOrderUpdateDto dto) {
        Long id = IdUtil.parseRequiredLongId(returnOrderId, "退货单ID");
        log.info("编辑统一退货单草稿，ID: {}, 参数: {}", id, dto);
        return Result.ok(returnOrderService.update(id, dto));
    }

    /**
     * 删除统一退货单草稿（仅 DRAFT 可删除）。
     *
     * @param returnOrderId 退货单ID
     * @param dto 乐观锁版本号请求
     * @return 空结果
     */
    @DeleteMapping("/{returnOrderId}")
    @Operation(summary = "删除统一退货单草稿")
    public Result<Void> delete(
            @Parameter(description = "退货单ID", required = true)
            @PathVariable String returnOrderId,
            @Valid @RequestBody OptimisticLockVersionDto dto) {
        Long id = IdUtil.parseRequiredLongId(returnOrderId, "退货单ID");
        log.info("删除统一退货单草稿，ID: {}, 版本: {}", id, dto.getVersion());
        returnOrderService.delete(id, dto.getVersion());
        return Result.ok();
    }

    /**
     * 提交统一退货单（DRAFT -> SUBMITTED）。
     *
     * @param returnOrderId 退货单ID
     * @param dto 乐观锁版本号请求
     * @return 空结果
     */
    @PostMapping("/{returnOrderId}/submit")
    @Operation(summary = "提交统一退货单")
    public Result<Void> submit(
            @Parameter(description = "退货单ID", required = true)
            @PathVariable String returnOrderId,
            @Valid @RequestBody OptimisticLockVersionDto dto) {
        Long id = IdUtil.parseRequiredLongId(returnOrderId, "退货单ID");
        log.info("提交统一退货单，ID: {}, 版本: {}", id, dto.getVersion());
        returnOrderService.submit(id, dto.getVersion());
        return Result.ok();
    }

    /** 查询退货单统一使用 return:query 权限，不区分采购/销售方向。 */
    private void checkQueryPermission(ReturnType type) {
        if (type == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "退货类型不能为空");
        }
        StpUtil.checkPermission("return:query");
    }

    /** 创建退货单按业务方向校验权限：采购退货需 purchase:create，销售退货需 sales:create。 */
    private void checkCreatePermission(ReturnType type) {
        if (type == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "退货类型不能为空");
        }
        StpUtil.checkPermission(type == ReturnType.PURCHASE_RETURN ? "purchase:create" : "sales:create");
    }
}