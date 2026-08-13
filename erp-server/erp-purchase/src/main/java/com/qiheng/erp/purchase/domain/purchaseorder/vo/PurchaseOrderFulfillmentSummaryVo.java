package com.qiheng.erp.purchase.domain.purchaseorder.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.qiheng.erp.common.config.MoneyStringSerializer;
import lombok.Data;
import lombok.experimental.Accessors;

import java.math.BigDecimal;

/** 采购订单按金额核算的履约摘要。 */
@Data
@Accessors(chain = true)
@Schema(description = "采购订单履约金额摘要")
public class PurchaseOrderFulfillmentSummaryVo {

    @Schema(description = "计算口径，固定为 AMOUNT_WEIGHTED")
    private String calculationMode;

    @Schema(description = "订单总金额")
    @JsonSerialize(using = MoneyStringSerializer.class)
    private BigDecimal totalAmount;

    @Schema(description = "累计入库金额")
    @JsonSerialize(using = MoneyStringSerializer.class)
    private BigDecimal inboundAmount;

    @Schema(description = "按金额核算的完成率，范围 0-100")
    private BigDecimal completionRate;
}
