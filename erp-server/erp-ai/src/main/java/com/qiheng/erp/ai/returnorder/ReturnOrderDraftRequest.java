package com.qiheng.erp.ai.returnorder;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.List;

/**
 * 退货草稿工具参数：只包含用户可决策的业务字段；来源单状态、可退数量和金额由服务端派生。
 *
 * @author Li
 * @since 2026-11-24
 */
public record ReturnOrderDraftRequest(
        @Nullable
        @JsonPropertyDescription("退货单数字 ID（纯数字字符串，例如 2125842152243126274），不是 PR/SR 单号。"
                + "修改已有退货单时必填，创建时不需要。")
        String returnOrderId,

        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，退货方向。枚举："
                + "SALES_RETURN=客户退货，来源是销售单，审核后生成退货入库单；"
                + "PURCHASE_RETURN=退回供应商，来源是采购单，审核后生成退货出库单。")
        String returnType,

        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，来源订单主键 ID（纯数字）。销售退货是销售单 ID，采购退货是采购单 ID；"
                + "使用 query_returnable_items 返回的 sourceOrderId，不要传业务编码。")
        String sourceOrderId,

        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，退货仓库主键 ID（纯数字）。必须等于来源单仓库，不能换仓退货。")
        String warehouseId,

        @Nullable
        @JsonPropertyDescription("可选，预计执行日期，格式 yyyy-MM-dd。草稿可以为空；"
                + "提交前必须补上；填写时不得早于今天。")
        String expectedExecutionDate,

        @Nullable
        @JsonPropertyDescription("可选，处理方式。枚举："
                + "REFUND=退款/退回货款（默认）；"
                + "EXCHANGE=换货；"
                + "OTHER=其他。"
                + "用户没有明确说换货或其他时使用 REFUND，不传也按 REFUND 处理。")
        String handlingType,

        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，退货原因码。枚举："
                + "QUALITY_ISSUE=质量问题；"
                + "DAMAGED=商品损坏；"
                + "WRONG_ITEM=商品错发；"
                + "QUANTITY_ERROR=数量错误；"
                + "SPEC_MISMATCH=规格不符；"
                + "NO_LONGER_NEEDED=不再需要；"
                + "OTHER=其他原因。"
                + "选择 OTHER 时，returnReason 必须写清具体原因。")
        String reasonCode,

        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，退货原因说明，写清退货事实。选择 OTHER 时必须具体。")
        String returnReason,

        @Nullable
        @JsonPropertyDescription("可选，表头备注。只在用户明确要求记录说明时填写。")
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
            @JsonPropertyDescription("必填，来源订单明细 ID，不是产品 ID。"
                    + "必须使用 query_returnable_items 返回的 sourceOrderItemId。")
            String sourceOrderItemId,

            @JsonProperty(required = true)
            @JsonPropertyDescription("必填，申请退货数量，必须大于 0、符合产品精度，"
                    + "且不能超过 query_returnable_items 返回的 availableReturnQty。")
            BigDecimal requestedQty,

            @Nullable
            @JsonPropertyDescription("可选，明细行备注。")
            String remark) {
    }
}