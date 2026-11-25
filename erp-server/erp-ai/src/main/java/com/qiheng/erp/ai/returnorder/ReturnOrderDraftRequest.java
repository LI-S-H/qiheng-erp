package com.qiheng.erp.ai.returnorder;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.List;

/**
 * 退货草稿工具参数：只包含用户可决策的业务字段，来源单状态和可退量由只读查询工具提供。
 *
 * @author Li
 * @since 2026-11-24
 */
public record ReturnOrderDraftRequest(
        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，退货方向：SALES_RETURN（客户退货）或 PURCHASE_RETURN（退回供应商）。")
        String returnType,

        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，来源订单 ID。销售退货填销售单 ID，采购退货填采购单 ID。")
        String sourceOrderId,

        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，退货仓库 ID。必须等于来源订单仓库，不能换仓。")
        String warehouseId,

        @Nullable
        @JsonPropertyDescription("预计执行日期，格式 yyyy-MM-dd；草稿可空，不能早于今天，提交前必须填写。")
        String expectedExecutionDate,

        @Nullable
        @JsonPropertyDescription("处理方式：REFUND（退款，默认）、EXCHANGE（换货）、OTHER（其他）。")
        String handlingType,

        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，退货原因编码，只能从 7 个枚举中选择。")
        String reasonCode,

        @Nullable
        @JsonPropertyDescription("退货原因说明，写清退货事实；选择 OTHER 时必须具体。")
        String returnReason,

        @Nullable
        @JsonPropertyDescription("表头备注，可填。")
        String remark,

        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，退货明细数组，至少一行。")
        List<Item> items) {

    /**
     * 退货明细行。
     *
     * @author Li
     * @since 2026-11-24
     */
    public record Item(
            @JsonProperty(required = true)
            @JsonPropertyDescription("必填，来源订单明细 ID，不是产品 ID。")
            String sourceOrderItemId,

            @JsonProperty(required = true)
            @JsonPropertyDescription("必填，申请退回数量，按产品最小单位填写，不使用已乘 100 的存储值。")
            BigDecimal requestedQty,

            @Nullable
            @JsonPropertyDescription("可选，明细行备注。")
            String remark) {
    }
}