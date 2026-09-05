package com.qiheng.erp.sales.domain.salesorder.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.qiheng.erp.common.config.MoneyStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 销售订单的退货概览，只读展示，不参与销售订单主状态流转。 */
@Data
@Schema(description = "销售订单退货概览")
public class SalesOrderReturnOverviewVo {

    @Schema(description = "是否存在关联退货单，包含已取消退货单")
    private boolean hasReturnOrder;

    @Schema(description = "退货覆盖度：NONE、PARTIAL、FULL")
    private String coverage;

    @Schema(description = "累计审批通过的销售退货金额")
    @JsonSerialize(using = MoneyStringSerializer.class)
    private BigDecimal approvedReturnAmount;

    @Schema(description = "关联退货单数")
    private Integer returnOrderCount;

    @Schema(description = "有效审批退货单数")
    private Integer effectiveReturnOrderCount;

    @Schema(description = "退货明细覆盖情况，仅订单详情返回")
    private List<SalesOrderReturnItemOverviewVo> items;
}
