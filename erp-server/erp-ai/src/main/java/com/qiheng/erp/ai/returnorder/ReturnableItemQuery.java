package com.qiheng.erp.ai.returnorder;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

/**
 * 可退明细查询参数。
 *
 * @author Li
 * @since 2026-11-24
 */
public record ReturnableItemQuery(
        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，退货方向：SALES_RETURN（客户退货）或 PURCHASE_RETURN（退回供应商）。")
        String returnType,

        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，来源订单号，如销售单 SO2026112300001 或采购单 PO2026112300001。")
        String sourceOrderNo) {
}