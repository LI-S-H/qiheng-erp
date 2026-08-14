package com.qiheng.erp.warehouse.mapper;

import com.qiheng.erp.warehouse.domain.stockbill.entity.StockBill;
import com.github.yulichang.base.MPJBaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 库存流水凭证主表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-07-19
 */
public interface StockBillMapper extends MPJBaseMapper<StockBill> {

    @Select("""
            SELECT COALESCE(MAX(CAST(RIGHT(bill_no, #{width}) AS UNSIGNED)), 0)
            FROM stock_bill
            WHERE REGEXP_LIKE(bill_no, CONCAT('^', #{dayPrefix}, '[0-9]{', #{width}, '}$'), 'c')
            """)
    Long findMaxStockBillNoSequence(@Param("dayPrefix") String dayPrefix, @Param("width") int width);
}