package com.qiheng.erp.ai.order;

import com.qiheng.erp.ai.action.PurchaseOrderActionReceipt;
import com.qiheng.erp.ai.draft.PurchaseDraftReceipt;

import java.util.Map;

/**
 * 采购单确认策略：与销售单共用模板，仅映射采购字段。
 *
 * @author Li
 * @since 2026-11-25
 */
public final class PurchaseOrderConfirmationStrategy implements OrderConfirmationStrategy {

    private static final String ORDER_TYPE = "PURCHASE";
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
        if (!(receipt instanceof PurchaseDraftReceipt value)) {
            throw new IllegalArgumentException("回执类型与采购草稿工具不匹配");
        }
        return new OrderDraftConfirmation(
                OrderTypeProfile.PURCHASE,
                value.status(),
                ACTION_CREATE.equals(action),
                value.purchaseOrderId(),
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
        if (!(receipt instanceof PurchaseOrderActionReceipt value)) {
            throw new IllegalArgumentException("回执类型与采购单动作工具不匹配");
        }
        return new OrderActionConfirmation(
                OrderTypeProfile.PURCHASE,
                value.status(),
                value.purchaseOrderId(),
                value.orderNo(),
                value.orderStatus(),
                value.error(),
                value.reason(),
                ACTION_SUBMIT.equals(action) ? "提交" : "取消",
                OrderTypeProfile.PURCHASE.dateLabel(),
                value.arrivalDate(),
                value.orderParams());
    }
}