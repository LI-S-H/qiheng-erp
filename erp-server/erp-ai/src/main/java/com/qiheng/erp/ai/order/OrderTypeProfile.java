package com.qiheng.erp.ai.order;

import java.util.List;
import java.util.Map;

/**
 * 单据类型的文案与字段差异；模板和参数清洗只依赖本对象，不再按销售/采购分支。
 *
 * @param orderName 单据名称，例如“销售单”
 * @param idField 主键字段名，例如 salesOrderId
 * @param dateLabel 日期字段名称，例如“预计发货”
 * @param headerFields 表头业务字段
 * @param itemFields 明细业务字段
 * @param statusTexts 单据状态文案
 * @author Li
 * @since 2026-11-25
 */
public record OrderTypeProfile(
        String orderName,
        String idField,
        String dateLabel,
        List<String> headerFields,
        List<String> itemFields,
        Map<String, String> statusTexts) {

    /** 销售单。 */
    public static final OrderTypeProfile SALES = new OrderTypeProfile(
            "销售单",
            "salesOrderId",
            "预计发货",
            List.of("customerId", "warehouseId", "expectedDeliveryDate", "remark"),
            List.of("productId", "quantity", "unitPrice", "remark"),
            Map.of(
                    "DRAFT", "草稿",
                    "SUBMITTED", "待审核",
                    "APPROVED", "待出库",
                    "PARTIAL_OUTBOUND", "部分出库",
                    "OUTBOUND_DONE", "已出库",
                    "CANCELLED", "已取消"));

    /** 采购单。 */
    public static final OrderTypeProfile PURCHASE = new OrderTypeProfile(
            "采购单",
            "purchaseOrderId",
            "预计到货",
            List.of("supplierId", "warehouseId", "expectedArrivalDate", "remark"),
            List.of("productId", "quantity", "unitPrice", "remark"),
            Map.of(
                    "DRAFT", "草稿",
                    "SUBMITTED", "待审核",
                    "APPROVED", "待入库",
                    "PARTIAL_INBOUND", "部分入库",
                    "INBOUND_DONE", "已入库",
                    "CANCELLED", "已取消"));

    /**
     * 把状态编码转成用户可读文字；未知状态原样返回。
     *
     * @param status 状态编码
     * @return 状态文案；空值返回 null
     */
    public String statusText(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        return statusTexts.getOrDefault(status, status);
    }
}