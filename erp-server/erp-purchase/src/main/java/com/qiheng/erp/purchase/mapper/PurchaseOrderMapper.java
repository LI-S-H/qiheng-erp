package com.qiheng.erp.purchase.mapper;

import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 采购订单主表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
public interface PurchaseOrderMapper extends BaseMapper<PurchaseOrder> {

    /**
     * 逻辑删除记录仍占用唯一采购单号，因此刻意不附加 deleted 条件。
     */
    @Select("""
            SELECT COALESCE(MAX(CAST(RIGHT(purchase_no, #{width}) AS UNSIGNED)), 0)
            FROM purchase_order
            WHERE REGEXP_LIKE(purchase_no, CONCAT('^', #{dayPrefix}, '[0-9]{', #{width}, '}$'), 'c')
            """)
    Long findMaxPurchaseNoSequence(@Param("dayPrefix") String dayPrefix, @Param("width") int width);
}