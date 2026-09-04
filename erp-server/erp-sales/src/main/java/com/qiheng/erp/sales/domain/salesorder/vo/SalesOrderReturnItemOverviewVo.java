package com.qiheng.erp.sales.domain.salesorder.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.qiheng.erp.common.config.MoneyStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/** 销售订单明细的退货审批覆盖情况。 */
@Data
@Schema(description = "销售订单明细退货概览")
public class SalesOrderReturnItemOverviewVo {

    @Schema(description = "销售订单明细 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long salesOrderItemId;

    @Schema(description = "订单原始数量")
    private BigDecimal orderedQty;

    @Schema(description = "已出库数量")
    private BigDecimal fulfilledQty;

    @Schema(description = "累计审批退货数量")
    private BigDecimal approvedReturnQty;

    @Schema(description = "累计审批退货金额")
    @JsonSerialize(using = MoneyStringSerializer.class)
    private BigDecimal approvedReturnAmount;
}
