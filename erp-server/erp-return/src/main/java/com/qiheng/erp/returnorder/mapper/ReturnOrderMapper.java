package com.qiheng.erp.returnorder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 统一退货单主表 Mapper。 */
public interface ReturnOrderMapper extends BaseMapper<ReturnOrder> {

    /**
     * 采购退货和销售退货共用表，逻辑删除记录也必须参与单号恢复。
     */
    @Select("""
            SELECT COALESCE(MAX(CAST(RIGHT(return_no, #{width}) AS UNSIGNED)), 0)
            FROM return_order
            WHERE REGEXP_LIKE(return_no, CONCAT('^', #{dayPrefix}, '[0-9]{', #{width}, '}$'), 'c')
            """)
    Long findMaxReturnNoSequence(@Param("dayPrefix") String dayPrefix, @Param("width") int width);
}