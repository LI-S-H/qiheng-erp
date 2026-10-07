package com.qiheng.erp.purchase.mapper;

import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

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
     * 定位质量窗口内包含目标供货产品的完全入库订单，EXISTS 避免同单多行重复。
     * 缺失完成时间仅供告警查询；LIMIT 是单页大小，不限制总样本数量。
     *
     * @param supplierId 供应商 ID
     * @param windowStart 质量窗口起点，包含
     * @param windowEnd 质量窗口终点，不包含
     * @param supplierProductIds 本页查询的目标供货产品集合，调用方保证非空且分批
     * @param cursor 上一页采购单 ID
     * @param pageSize 单页采购单数量
     * @param missingCompletionTime 是否只查询缺失完成时间的异常订单
     * @return 按采购单 ID 升序排列的候选订单
     */
    @Select("""
            <script>
            SELECT po.id, po.supplier_id, po.status, po.fully_received_at, po.purchase_no
            FROM purchase_order po
            WHERE po.deleted = 0 AND po.supplier_id = #{supplierId}
              AND po.status = 'INBOUND_DONE' AND po.id &gt; #{cursor}
            <choose>
              <when test="missingCompletionTime">AND po.fully_received_at IS NULL</when>
              <otherwise>
                AND po.fully_received_at &gt;= #{windowStart}
                AND po.fully_received_at &lt; #{windowEnd}
              </otherwise>
            </choose>
              AND EXISTS (
                SELECT 1 FROM purchase_order_item item
                WHERE item.purchase_order_id = po.id
                  AND item.supplier_product_id IN
                  <foreach collection="supplierProductIds" item="id" open="(" separator="," close=")">#{id}</foreach>
              )
            ORDER BY po.id ASC LIMIT #{pageSize}
            </script>
            """)
    List<PurchaseOrder> selectQualityOrdersForProducts(@Param("supplierId") Long supplierId,
                                                     @Param("windowStart") LocalDateTime windowStart,
                                                     @Param("windowEnd") LocalDateTime windowEnd,
                                                     @Param("supplierProductIds") Set<Long> supplierProductIds,
                                                     @Param("cursor") long cursor,
                                                     @Param("pageSize") int pageSize,
                                                     @Param("missingCompletionTime") boolean missingCompletionTime);

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
