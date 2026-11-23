package com.qiheng.erp.ai.sales;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.List;

/**
 * 销售草稿工具参数：只包含用户决策层面的字段，金额合计由前端计算。
 *
 * @author Li
 * @since 2026-11-23
 */
public record SalesOrderDraftRequest(
        @Nullable
        @JsonPropertyDescription("销售单 ID。修改已有销售单时必填，创建时不需要。")
        String salesOrderId,

        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，客户 ID。必须是系统内真实存在的客户编号，不要编造。")
        String customerId,

        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，出库仓库 ID。必须是系统内真实存在的仓库编号，不要编造。")
        String warehouseId,

        @Nullable
        @JsonPropertyDescription("预计发货日期，格式 yyyy-MM-dd；没有明确依据时留空。")
        String expectedDeliveryDate,

        @Nullable
        @JsonPropertyDescription("表头备注，可填。只在用户明确要求记录说明时填写用户给出的内容；不要写 AI 的计算依据、生成过程或来源说明。")
        String remark,

        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，销售明细数组，至少一行。")
        List<Item> items) {

    /**
     * 销售明细行。
     *
     * @author Li
     * @since 2026-11-23
     */
    public record Item(
            @JsonProperty(required = true)
            @JsonPropertyDescription("必填，产品 ID。必须是启用状态的产品。")
            String productId,

            @JsonProperty(required = true)
            @JsonPropertyDescription("必填，销售数量，按产品最小单位填写，不使用已乘 100 的存储值。")
            BigDecimal quantity,

            @JsonProperty(required = true)
            @JsonPropertyDescription("必填，销售单价，单位元。用产品的参考售价或用户给定的价格，不要编造。")
            BigDecimal unitPrice,

            @Nullable
            @JsonPropertyDescription("可选，明细行备注。")
            String remark) {
    }
}