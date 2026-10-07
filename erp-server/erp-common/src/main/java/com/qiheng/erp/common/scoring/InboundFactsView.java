package com.qiheng.erp.common.scoring;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 180 天入库事实明细视图(供应商评分事实查询契约)。
 *
 * <p>定义在 erp-common,三个模块共享:</p>
 * <ul>
 *   <li>erp-warehouse 提供 {@code InboundBillFactsQueryServiceImpl} 实现</li>
 *   <li>erp-purchase 提供 {@code ScoreFactsQueryService} 调用方</li>
 * </ul>
 *
 * <p>字段语义:</p>
 * <ul>
 *   <li>supplierProductId:来源 purchase_order_item.supplier_product_id,SP 维度金额分组依据;NULL 表示无来源订单(手补录)</li>
 *   <li>qualifiedQty:本入库单的合格数量(放大 100 倍),null 表示该明细未登记</li>
 *   <li>unqualifiedQty:本入库单的非合格数量(放大 100 倍)</li>
 *   <li>totalAmount:本入库单的入库金额(分),用于 Java 端计算 penalty 与 SP 维度金额累计</li>
 *   <li>expectedArrivalDate:承诺到货日,业务快照字段(原 expected_arrival_date)</li>
 *   <li>confirmedAt:入库确认时间,用于算逾期天数(confirmedAt.toLocalDate() - expectedArrivalDate)</li>
 * </ul>
 *
 * @author Li
 * @since 2026-09-24
 */
public record InboundFactsView(
        Long supplierProductId,
        Long qualifiedQty,
        Long unqualifiedQty,
        Long totalAmount,
        LocalDate expectedArrivalDate,
        LocalDateTime confirmedAt
) {
}