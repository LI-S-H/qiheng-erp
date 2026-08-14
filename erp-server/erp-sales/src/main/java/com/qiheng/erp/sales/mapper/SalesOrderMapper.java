package com.qiheng.erp.sales.mapper;

import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 销售订单主表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
public interface SalesOrderMapper extends BaseMapper<SalesOrder> {

    /**
     * 逻辑删除记录仍占用唯一销售单号，因此刻意不附加 deleted 条件。
     */
    @Select("""
            SELECT COALESCE(MAX(CAST(RIGHT(sales_no, #{width}) AS UNSIGNED)), 0)
            FROM sales_order
            WHERE REGEXP_LIKE(sales_no, CONCAT('^', #{dayPrefix}, '[0-9]{', #{width}, '}$'), 'c')
            """)
    Long findMaxSalesNoSequence(@Param("dayPrefix") String dayPrefix, @Param("width") int width);
}