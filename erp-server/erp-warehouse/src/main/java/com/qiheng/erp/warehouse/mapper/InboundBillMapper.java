package com.qiheng.erp.warehouse.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 入库单主表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-07-23
 */
public interface InboundBillMapper extends MPJBaseMapper<InboundBill> {

    /**
     * 入库工作单逻辑删除后单号仍唯一，恢复序列时不能过滤 deleted。
     */
    @Select("""
            SELECT COALESCE(MAX(CAST(RIGHT(inbound_no, #{width}) AS UNSIGNED)), 0)
            FROM inbound_bill
            WHERE REGEXP_LIKE(inbound_no, CONCAT('^', #{dayPrefix}, '[0-9]{', #{width}, '}$'), 'c')
            """)
    Long findMaxInboundNoSequence(@Param("dayPrefix") String dayPrefix, @Param("width") int width);
}