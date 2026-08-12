package com.qiheng.erp.warehouse.mapper;

import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBillItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 入库单明细表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-07-23
 */
public interface InboundBillItemMapper extends BaseMapper<InboundBillItem> {

    /**
     * 累加同 source_item_id 已确认入库单的 current_qty，用于 addItem 时计算 processed_qty
     * （生成本入库单前，该 source_item_id 已累计入库数量）。
     */
    @Select("SELECT COALESCE(SUM(t1.current_qty), 0) FROM inbound_bill_item t1 "
            + "JOIN inbound_bill t2 ON t1.inbound_bill_id = t2.id "
            + "WHERE t1.source_item_id = #{sourceItemId} "
            + "AND t2.status = 'CONFIRMED' AND t2.deleted = 0")
    Long sumConfirmedCurrentQtyBySourceItemId(@Param("sourceItemId") Long sourceItemId);
}
