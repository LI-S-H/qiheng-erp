package com.qiheng.erp.warehouse.mapper;

import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBill;
import com.github.yulichang.base.MPJBaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 出库单主表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-07-26
 */
public interface OutboundBillMapper extends MPJBaseMapper<OutboundBill> {

    /**
     * 出库工作单逻辑删除后单号仍唯一，恢复序列时不能过滤 deleted。
     */
    @Select("""
            SELECT COALESCE(MAX(CAST(RIGHT(outbound_no, #{width}) AS UNSIGNED)), 0)
            FROM outbound_bill
            WHERE REGEXP_LIKE(outbound_no, CONCAT('^', #{dayPrefix}, '[0-9]{', #{width}, '}$'), 'c')
            """)
    Long findMaxOutboundNoSequence(@Param("dayPrefix") String dayPrefix, @Param("width") int width);
}