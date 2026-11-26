package com.qiheng.erp.ai.returnorder;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.lang.Nullable;

/**
 * 可退明细查询参数。
 *
 * @author Li
 * @since 2026-11-24
 */
public record ReturnableItemQuery(
        @JsonPropertyDescription("退货方向。枚举："
                + "SALES_RETURN=客户退货，来源是销售单；"
                + "PURCHASE_RETURN=退回供应商，来源是采购单。"
                + "传 returnOrderId 时可省略，按退货单方向查询。")
        String returnType,

        @Nullable
        @JsonPropertyDescription("来源订单号，如销售单 SO2026112300001 或采购单 PO2026112300001。"
                + "创建退货草稿时必填；传 returnOrderId 时可省略。")
        String sourceOrderNo,

        @Nullable
        @JsonPropertyDescription("退货单 ID。修改已有退货单时传，用于排除该退货单自身占用。")
        String returnOrderId) {
}