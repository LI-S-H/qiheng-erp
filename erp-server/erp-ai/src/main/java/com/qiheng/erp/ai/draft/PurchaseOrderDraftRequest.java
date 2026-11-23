package com.qiheng.erp.ai.draft;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.lang.Nullable;

import java.math.BigDecimal;
import java.util.List;

/**
 * 采购草稿工具参数：只包含用户决策层面的字段，供货关系编号、金额合计由前端补齐。
 *
 * @author Li
 * @since 2026-11-22
 */
public record PurchaseOrderDraftRequest(
        @Nullable
        @JsonPropertyDescription("采购单 ID。修改已有采购单时必填，创建时不需要。")
        String purchaseOrderId,

        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，供应商 ID。必须是系统内真实存在的供应商编号，不要编造。")
        String supplierId,

        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，入库仓库 ID。必须是系统内真实存在的仓库编号，不要编造。")
        String warehouseId,

        @Nullable
        @JsonPropertyDescription("预计到货日期，格式 yyyy-MM-dd；没有明确依据时留空。")
        String expectedArrivalDate,

        @Nullable
        @JsonPropertyDescription("表头备注，可填。只在用户明确要求记录说明时填写用户给出的内容；不要写 AI 的计算依据、生成过程或来源说明。")
        String remark,

        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，采购明细数组，至少一行。")
        List<Item> items) {

    /**
     * 采购明细行。
     *
     * @author Li
     * @since 2026-11-22
     */
    public record Item(
            @JsonProperty(required = true)
            @JsonPropertyDescription("必填，产品 ID。必须是该供应商已维护启用供货关系的产品。")
            String productId,

            @JsonProperty(required = true)
            @JsonPropertyDescription("必填，采购数量，按产品最小单位填写，不使用已乘 100 的存储值。")
            BigDecimal quantity,

            @Nullable
            @JsonPropertyDescription("可选，采购单价，单位元。")
            BigDecimal unitPrice,

            @Nullable
            @JsonPropertyDescription("可选，明细行备注。")
            String remark) {
    }
}