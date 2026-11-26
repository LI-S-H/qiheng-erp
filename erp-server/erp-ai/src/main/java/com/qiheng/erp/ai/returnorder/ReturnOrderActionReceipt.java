package com.qiheng.erp.ai.returnorder;

import com.qiheng.erp.ai.order.OrderReceipt;

import java.util.Map;

/**
 * 提交或取消退货单的用户处置结果，由前端确认卡片提交。
 *
 * @author Li
 * @since 2026-11-26
 */
public record ReturnOrderActionReceipt(
        String status,
        String returnOrderId,
        String returnType,
        String orderNo,
        String orderStatus,
        String error,
        String reason,
        String executionDate,
        Map<String, Object> orderParams) implements OrderReceipt {

    /** 用户同意提交。 */
    public static final String STATUS_SUBMITTED = "submitted";
    /** 用户同意取消。 */
    public static final String STATUS_CANCELLED = "cancelled";
    /** 用户暂不执行，保持原状。 */
    public static final String STATUS_REJECTED = "rejected";
    /** 系统接口拒绝执行。 */
    public static final String STATUS_FAILED = "failed";
}