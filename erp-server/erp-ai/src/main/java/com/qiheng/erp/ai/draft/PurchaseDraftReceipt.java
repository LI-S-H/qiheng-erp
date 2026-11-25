package com.qiheng.erp.ai.draft;

import java.util.List;
import java.util.Map;

/**
 * 采购草稿的用户处置结果；由确认页提交，工具据此把事实交回模型。
 *
 * <p>{@code finalParams} 必须与工具参数结构一致，只描述用户最终确认的业务字段，
 * 不包含供货关系编号、金额合计等前端派生数据。</p>
 *
 * <p>{@code originalParams} 是确认卡片打开时的参数，用于区分“用户直接批准模型建议”
 * 和“用户改了参数后保存”；只有 {@code edited} 需要携带。</p>
 *
 * @author Li
 * @since 2026-11-22
 */
public record PurchaseDraftReceipt(
        String status,
        String orderNo,
        String purchaseOrderId,
        String orderStatus,
        String error,
        Map<String, Object> finalParams,
        String userNote,
        List<String> reasons,
        String suggestion,
        Map<String, Object> originalParams) {

    /** 兼容不携带基线参数的旧调用。 */
    public PurchaseDraftReceipt(String status, String orderNo, String purchaseOrderId, String orderStatus,
                                String error, Map<String, Object> finalParams, String userNote,
                                List<String> reasons, String suggestion) {
        this(status, orderNo, purchaseOrderId, orderStatus, error, finalParams, userNote, reasons, suggestion, null);
    }

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