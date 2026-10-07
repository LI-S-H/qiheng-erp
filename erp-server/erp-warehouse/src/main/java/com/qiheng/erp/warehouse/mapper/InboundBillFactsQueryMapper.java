package com.qiheng.erp.warehouse.mapper;

import com.qiheng.erp.warehouse.domain.scoring.InboundFactsRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入库单事实聚合查询 Mapper。
 *
 * <p>实现 {@code com.qiheng.erp.purchase.service.scoring.InboundBillFactsQueryService}
 * 接口的 SQL 部分,数据从 {@code inbound_bill} + {@code inbound_bill_item} + {@code purchase_order_item}
 * 三表 join 后 GROUP BY 聚合。
 *
 * <p>本版本依赖 {@code inbound_bill_item.unit_price} 快照列
 * (参见 DDL 010_inbound_bill_item_unit_price_snapshot.sql);并通过 LEFT JOIN
 * {@code purchase_order_item} 拿到 {@code supplier_product_id} 作为 SP 维度金额分组依据。</p>
 *
 * @author Li
 * @since 2026-09-24
 */
@Mapper
public interface InboundBillFactsQueryMapper {

    /**
     * 查 supplier 180 天已完全入库(PURCHASE_IN + CONFIRMED)的入库单聚合行。
     *
     * <p>返回结果按 confirmed_at 升序;无数据返回空列表。</p>
     *
     * @param supplierId 目标供应商 ID(inbound_bill.source_party_id)
     * @param since 窗口起点(通常 = today - 180 天 00:00:00)
     * @return 入库单级别聚合行(含 supplierProductId,NULL 表示手补录/调整入库)
     */
    List<InboundFactsRow> selectRecentInboundFacts(@Param("supplierId") Long supplierId,
                                                 @Param("since") LocalDateTime since);
}
