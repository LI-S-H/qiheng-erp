package com.qiheng.erp.purchase.domain.purchaseorder.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * <p>
 * 采购订单详情 VO（主表 + 明细列表）
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "采购订单详情")
public class PurchaseOrderDetailVo extends PurchaseOrderVo {

    @Schema(description = "采购订单明细列表")
    private List<PurchaseOrderItemVo> items;

    @Schema(description = "采购订单履约金额摘要")
    private PurchaseOrderFulfillmentSummaryVo fulfillmentSummary;

    @Schema(description = "采购订单流程记录")
    private List<PurchaseOrderTimelineItemVo> timeline;
}
