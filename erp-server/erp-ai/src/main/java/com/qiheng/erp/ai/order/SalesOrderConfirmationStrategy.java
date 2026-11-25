package com.qiheng.erp.ai.order;

import com.qiheng.erp.ai.sales.SalesOrderActionReceipt;
import com.qiheng.erp.ai.sales.SalesOrderDraftReceipt;

import java.util.Map;

/**
 * 销售单确认策略：只做字段映射，文案和参数处理复用公共模板。
 *
 * @author Li
 * @since 2026-11-25
 */
public final class SalesOrderConfirmationStrategy implements OrderConfirmationStrategy {

    private static final String ORDER_TYPE = "SALES";
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
        if (!(receipt instanceof SalesOrderDraftReceipt value)) {
            throw new IllegalArgumentException("回执类型与销售草稿工具不匹配");
        }
        return new OrderDraftConfirmation(
                OrderTypeProfile.SALES,
                value.status(),
                ACTION_CREATE.equals(action),
                value.salesOrderId(),
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
        if (!(receipt instanceof SalesOrderActionReceipt value)) {
            throw new IllegalArgumentException("回执类型与销售单动作工具不匹配");
        }
        return new OrderActionConfirmation(
                OrderTypeProfile.SALES,
                value.status(),
                value.salesOrderId(),
                value.orderNo(),
                value.orderStatus(),
                value.error(),
                value.reason(),
                ACTION_SUBMIT.equals(action) ? "提交" : "取消",
                OrderTypeProfile.SALES.dateLabel(),
                value.deliveryDate(),
                value.orderParams());
    }
}