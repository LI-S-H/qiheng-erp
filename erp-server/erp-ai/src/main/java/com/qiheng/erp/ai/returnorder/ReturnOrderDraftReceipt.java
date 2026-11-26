package com.qiheng.erp.ai.returnorder;

import com.qiheng.erp.ai.order.OrderReceipt;

import java.util.List;
import java.util.Map;

/**
 * 退货草稿的用户处置结果；由确认页提交，工具据此把事实交回模型。
 *
 * @author Li
 * @since 2026-11-26
 */
public record ReturnOrderDraftReceipt(
        String status,
        String orderNo,
        String returnOrderId,
        String returnType,
        String orderStatus,
        String error,
        Map<String, Object> finalParams,
        String userNote,
        List<String> reasons,
        String suggestion,
        Map<String, Object> originalParams) implements OrderReceipt {

    /** 直接确认创建。 */
    public static final String STATUS_CREATED = "created";
    /** 直接确认修改。 */
    public static final String STATUS_UPDATED = "updated";
    /** 用户修改参数后创建或保存。 */
    public static final String STATUS_EDITED = "edited";
    /** 系统接口拒绝了操作。 */
    public static final String STATUS_FAILED = "failed";
    /** 用户驳回草稿或修改。 */
    public static final String STATUS_REJECTED = "rejected";
}