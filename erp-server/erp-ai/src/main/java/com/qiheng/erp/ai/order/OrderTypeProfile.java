package com.qiheng.erp.ai.order;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单据类型的文案与字段差异；模板和参数清洗只依赖本对象，不再按销售/采购分支。
 *
 * @param orderName 单据名称，例如“销售单”
 * @param idField 主键字段名，例如 salesOrderId
 * @param dateLabel 日期字段名称，例如“预计发货”
 * @param returnType 退货方向；普通单据为 null
 * @param headerFields 表头业务字段
 * @param itemFields 明细业务字段
 * @param itemIdField 明细主键字段，例如 productId 或 sourceOrderItemId
 * @param itemQtyField 明细数量字段，例如 quantity 或 requestedQty
 * @param numericFields 按数值比较的字段
 * @param statusTexts 单据状态文案
 * @author Li
 * @since 2026-11-25
 */
public record OrderTypeProfile(
        String orderName,
        String idField,
        String dateLabel,
        String returnType,
        List<String> headerFields,
        List<String> itemFields,
        String itemIdField,
        String itemQtyField,
        Set<String> numericFields,
        Map<String, String> statusTexts) {

    /** 销售单。 */
    public static final OrderTypeProfile SALES = new OrderTypeProfile(
            "销售单",
            "salesOrderId",
            "预计发货",
            null,
            List.of("customerId", "warehouseId", "expectedDeliveryDate", "remark"),
            List.of("productId", "quantity", "unitPrice", "remark"),
            "productId",
            "quantity",
            Set.of("quantity", "unitPrice"),
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
            null,
            List.of("supplierId", "warehouseId", "expectedArrivalDate", "remark"),
            List.of("productId", "quantity", "unitPrice", "remark"),
            "productId",
            "quantity",
            Set.of("quantity", "unitPrice"),
            Map.of(
                    "DRAFT", "草稿",
                    "SUBMITTED", "待审核",
                    "APPROVED", "待入库",
                    "PARTIAL_INBOUND", "部分入库",
                    "INBOUND_DONE", "已入库",
                    "CANCELLED", "已取消"));

    /** 销售退货单。 */
    public static final OrderTypeProfile SALES_RETURN = new OrderTypeProfile(
            "销售退货单",
            "returnOrderId",
            "预计执行",
            "SALES_RETURN",
            List.of("sourceOrderId", "warehouseId", "expectedExecutionDate",
                    "handlingType", "reasonCode", "returnReason", "remark"),
            List.of("sourceOrderItemId", "requestedQty", "remark"),
            "sourceOrderItemId",
            "requestedQty",
            Set.of("requestedQty"),
            Map.of(
                    "DRAFT", "草稿",
                    "SUBMITTED", "待审核",
                    "APPROVED", "待入库",
                    "PARTIAL_EXECUTED", "部分执行",
                    "COMPLETED", "已完成",
                    "CANCELLED", "已取消"));

    /** 采购退货单。 */
    public static final OrderTypeProfile PURCHASE_RETURN = new OrderTypeProfile(
            "采购退货单",
            "returnOrderId",
            "预计执行",
            "PURCHASE_RETURN",
            List.of("sourceOrderId", "warehouseId", "expectedExecutionDate",
                    "handlingType", "reasonCode", "returnReason", "remark"),
            List.of("sourceOrderItemId", "requestedQty", "remark"),
            "sourceOrderItemId",
            "requestedQty",
            Set.of("requestedQty"),
            Map.of(
                    "DRAFT", "草稿",
                    "SUBMITTED", "待审核",
                    "APPROVED", "待出库",
                    "PARTIAL_EXECUTED", "部分执行",
                    "COMPLETED", "已完成",
                    "CANCELLED", "已取消"));

    /**
     * 按退货方向选择退货 profile。
     *
     * @param returnType 退货方向
     * @return 对应 profile
     */
    public static OrderTypeProfile ofReturnType(String returnType) {
        if ("SALES_RETURN".equals(returnType)) {
            return SALES_RETURN;
        }
        if ("PURCHASE_RETURN".equals(returnType)) {
            return PURCHASE_RETURN;
        }
        throw new IllegalArgumentException("不支持的退货方向：" + returnType);
    }

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