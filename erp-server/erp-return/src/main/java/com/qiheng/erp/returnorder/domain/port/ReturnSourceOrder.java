package com.qiheng.erp.returnorder.domain.port;

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
        String warehouseName) {}
