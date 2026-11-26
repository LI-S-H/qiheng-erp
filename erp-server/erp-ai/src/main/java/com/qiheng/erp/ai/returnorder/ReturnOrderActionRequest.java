package com.qiheng.erp.ai.returnorder;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import org.springframework.lang.Nullable;

/**
 * 提交或取消退货单时交给用户的动作参数。
 *
 * @author Li
 * @since 2026-11-26
 */
public record ReturnOrderActionRequest(
        @JsonProperty(required = true)
        @JsonPropertyDescription("必填，要操作的退货单数字 ID（纯数字字符串，例如 2125842152243126274），"
                + "不是 PR/SR 开头的退货单号；使用查询退货单结果里的 id 字段。")
        String returnOrderId,

        @Nullable
        @JsonPropertyDescription("退货单号，便于回执引用；有就传。")
        String orderNo,

        @Nullable
        @JsonPropertyDescription("取消原因，从用户的话里提取；提交时不需要。")
        String reason) {
}