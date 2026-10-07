package com.qiheng.erp.warehouse.domain.scoring;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 入库单事实聚合行(供应商评分事实查询用)。
 *
 * <p>对应 mapper XML {@code InboundBillFactsQueryMapper.xml} 的查询结果,
 * 入库单级别一行 1 维度。本版本依赖 {@code inbound_bill_item.unit_price} 快照列,
 * 并通过 LEFT JOIN purchase_order_item 拿 supplier_product_id 作为 SP 维度分组依据。</p>
 *
 * <p>{@code supplierProductId} 为 {@code null} 表示该入库明细无来源订单
 * (手补录 / 调整入库),不入 SP 维度金额聚合。</p>
 *
 * @author Li
 * @since 2026-09-24
 */
public record InboundFactsRow(
        Long supplierProductId,
        LocalDate expectedArrivalDate,
        LocalDateTime confirmedAt,
        Long qualifiedQty,
        Long defectiveQty,
        Long totalAmount
) {
}