package com.qiheng.erp.ai.action;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.lang.Nullable;

/**
 * 提交或取消采购单时交给用户的动作参数。
 *
 * @author Li
 * @since 2026-11-23
 */
public record PurchaseOrderActionRequest(
        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，要操作的采购单 ID。必须来自创建或修改回执中的 purchaseOrderId。")
        String purchaseOrderId,

        @Nullable
        @JsonPropertyDescription("采购单号，便于回执引用；有就传。")
        String orderNo,

        @Nullable
        @JsonPropertyDescription("取消原因，从用户的话里提取；提交时不需要。")
        String reason) {
}