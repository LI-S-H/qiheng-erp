package com.qiheng.erp.returnorder.domain.port;

import java.time.LocalDate;

/** 退货来源订单的不可变快照。 */
public record ReturnSourceOrder(
        // 退货来源订单ID
        Long sourceOrderId,
        // 退货来源订单编号
        String sourceOrderNo,
        // 退货ID
        Long partyId,
        // 退货来源订单类型编码
        String partyCode,
        // 退货来源订单类型名称
        String partyName,
        // 仓库ID
        Long warehouseId,
        // 仓库名称
        String warehouseName,
        // 来源订单审批日（用于 TOP 商品排行口径与 30 天窗口判断；为空时回退到退货单自身的 approvedAt）
        LocalDate approvedDay) {
}