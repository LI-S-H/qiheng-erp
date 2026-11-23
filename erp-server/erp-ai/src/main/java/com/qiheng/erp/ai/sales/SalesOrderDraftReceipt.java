package com.qiheng.erp.ai.sales;

import java.util.List;
import java.util.Map;

/**
 * 销售草稿的用户处置结果；由确认页提交，工具据此把事实交回模型。
 *
 * <p>{@code finalParams} 必须与工具参数结构一致，只描述用户最终确认的业务字段，
 * 不包含金额合计等前端派生数据。</p>
 *
 * @author Li
 * @since 2026-11-22
 */
public record SalesOrderDraftReceipt(
        String status,
        String orderNo,
        String salesOrderId,
        String orderStatus,
        String error,
        Map<String, Object> finalParams,
        String userNote,
        List<String> reasons,
        String suggestion) {

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