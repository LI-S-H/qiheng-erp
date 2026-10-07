package com.qiheng.erp.purchase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrderItem;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 采购订单明细表 Mapper 接口
 * </p>
 */
public interface PurchaseOrderItemMapper extends MPJBaseMapper<PurchaseOrderItem> {

    /**
     * 仅查 supplier_product_id(供 PurchaseInboundWritebackPort 累加 score_basis_amount 用)。
     * 避免 selectById 拉整个实体,减少回表与 GC。
     */
    @Select("SELECT supplier_product_id FROM purchase_order_item WHERE id = #{id}")
    Long selectSupplierProductIdById(@Param("id") Long id);
}