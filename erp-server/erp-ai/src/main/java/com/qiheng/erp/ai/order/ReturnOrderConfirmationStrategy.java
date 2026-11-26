package com.qiheng.erp.ai.order;

import com.qiheng.erp.ai.returnorder.ReturnOrderActionReceipt;
import com.qiheng.erp.ai.returnorder.ReturnOrderDraftReceipt;

import java.util.Map;

/**
 * 退货单确认策略：只把退货回执映射到公共确认结构，文案和历史记录仍由公共模板生成。
 *
 * @author Li
 * @since 2026-11-26
 */
public final class ReturnOrderConfirmationStrategy implements OrderConfirmationStrategy {

    private static final String ORDER_TYPE = "RETURN";
    private static final String ACTION_SUBMIT = "SUBMIT";
    private static final String ACTION_CREATE = "CREATE";

    @Override
    public boolean supports(String toolName) {
        return ORDER_TYPE.equals(OrderToolNames.orderTypeOf(toolName));
    }

    @Override
    public String orderType() {
        return ORDER_TYPE;
    }

    @Override
    public OrderDraftConfirmation draftConfirmation(Object receipt, String action,
                                                    Map<String, Object> draft) {
        if (!(receipt instanceof ReturnOrderDraftReceipt value)) {
            throw new IllegalArgumentException("回执类型与退货草稿工具不匹配");
        }
        return new OrderDraftConfirmation(
                profileOf(value.returnType()),
                value.status(),
                ACTION_CREATE.equals(action),
                value.returnOrderId(),
                value.orderNo(),
                value.orderStatus(),
                draft,
                value.finalParams(),
                value.userNote(),
                value.reasons(),
                value.suggestion(),
                value.error());
    }

    @Override
    public OrderActionConfirmation actionConfirmation(Object receipt, String action) {
        if (!(receipt instanceof ReturnOrderActionReceipt value)) {
            throw new IllegalArgumentException("回执类型与退货单动作工具不匹配");
        }
        OrderTypeProfile profile = profileOf(value.returnType());
        return new OrderActionConfirmation(
                profile,
                value.status(),
                value.returnOrderId(),
                value.orderNo(),
                value.orderStatus(),
                value.error(),
                value.reason(),
                ACTION_SUBMIT.equals(action) ? "提交" : "取消",
                profile.dateLabel(),
                value.executionDate(),
                value.orderParams());
    }

    /** 按退货方向选择公共文案和字段配置。 */
    private OrderTypeProfile profileOf(String returnType) {
        if (returnType == null || returnType.isBlank()) {
            throw new IllegalArgumentException("退货回执缺少 returnType");
        }
        return OrderTypeProfile.ofReturnType(returnType);
    }
}