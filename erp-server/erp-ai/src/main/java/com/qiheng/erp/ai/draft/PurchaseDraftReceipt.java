package com.qiheng.erp.ai.draft;

import java.util.List;
import java.util.Map;

/**
 * 采购草稿的用户处置结果；由确认页提交，工具据此把事实交回模型。
 *
 * <p>{@code finalParams} 必须与工具参数结构一致，只描述用户最终确认的业务字段，
 * 不包含供货关系编号、金额合计等前端派生数据。</p>
 *
 * @author Li
 * @since 2026-11-22
 */
public record PurchaseDraftReceipt(
        String status,
        String orderNo,
        String purchaseOrderId,
        String error,
        Map<String, Object> finalParams,
        String userNote,
        List<String> reasons,
        String suggestion) {

    /** 直接确认创建。 */
    public static final String STATUS_CREATED = "created";
    /** 用户修改后创建。 */
    public static final String STATUS_EDITED = "edited";
    /** 系统接口拒绝了创建。 */
    public static final String STATUS_FAILED = "failed";
    /** 用户驳回草稿。 */
    public static final String STATUS_REJECTED = "rejected";
}