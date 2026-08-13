package com.qiheng.erp.returnorder.domain.port;

/** 可退货来源明细的不可变快照；数量均为放大 100 倍后的持久化值。 */
public record ReturnSourceItem(
        // 来源单明细ID
        Long sourceOrderItemId,
        // 产品ID
        Long productId,
        // 产品编码
        String productCode,
        // 产品名称
        String productName,
        // 单位名称
        String unitName,
        // 单位精度
        Integer quantityPrecision,
        // 已履约数量（采购退货为已入库数量，销售退货为已出库数量）
        Long fulfilledQty,
        // 单价
        Long unitPrice
) {
}
