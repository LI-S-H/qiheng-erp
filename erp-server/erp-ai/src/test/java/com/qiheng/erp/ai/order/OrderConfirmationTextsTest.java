package com.qiheng.erp.ai.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证七种确认结果的结论文本和展示记录：结论先行，参数块只在需要时追加。
 *
 * @author Li
 * @since 2026-11-25
 */
class OrderConfirmationTextsTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void draftCreatedTextStartsWithConclusionAndEndsWithOrderBlock() {
        String text = OrderConfirmationTexts.draftText(draft(
                OrderTypeProfile.SALES, "created", true, "9001", "SO001", "DRAFT",
                draftParams("2", "3", "1"), null, null, List.of(), null, null), mapper);

        assertThat(text).startsWith("用户批准了创建，销售单 SO001 已生成草稿。")
                .contains("\"salesOrderId\":\"9001\"")
                .contains("\"orderNo\":\"SO001\"");
    }

    @Test
    void draftUpdatedTextUsesRealOrderStatus() {
        String text = OrderConfirmationTexts.draftText(draft(
                OrderTypeProfile.SALES, "updated", false, "9001", "SO001", "SUBMITTED",
                draftParams("2", "3", "1"), draftParams("2", "3", "1"), null, List.of(), null, null), mapper);

        assertThat(text).contains("用户批准了修改，销售单 SO001 已保存。")
                .contains("当前状态为待审核。")
                .doesNotContain("最终参数：");
    }

    @Test
    void draftEditedTextReportsFinalParamsChangedFieldsAndUserNote() {
        Map<String, Object> finalParams = Map.of(
                "customerId", "2",
                "warehouseId", "3",
                "expectedDeliveryDate", "",
                "remark", "",
                "items", List.of(Map.of(
                        "productId", "4",
                        "quantity", new BigDecimal("2"),
                        "unitPrice", new BigDecimal("10.00"),
                        "remark", "")));

        String text = OrderConfirmationTexts.draftText(draft(
                OrderTypeProfile.SALES, "edited", false, "9001", "SO001", "DRAFT",
                draftParams("2", "3", "1"), finalParams, "客户改数量", List.of(), null, null), mapper);

        assertThat(text).contains("用户修改了参数并保存，销售单 SO001 已更新。")
                .contains("最终参数：")
                .contains("修改的字段：items[0].quantity")
                .contains("用户说明：客户改数量");
    }

    @Test
    void draftRejectedTextDiffersBetweenCreateAndUpdate() {
        String created = OrderConfirmationTexts.draftText(draft(
                OrderTypeProfile.SALES, "rejected", true, null, null, null,
                draftParams("2", "3", "1"), null, null, List.of("价格过高"), "希望下周再下单", null), mapper);
        String updated = OrderConfirmationTexts.draftText(draft(
                OrderTypeProfile.SALES, "rejected", false, "9001", "SO001", "SUBMITTED",
                draftParams("2", "3", "1"), null, null, List.of("价格过高"), "希望下周再下单", null), mapper);

        assertThat(created).contains("用户驳回了这次创建，未生成销售单。")
                .contains("驳回原因：价格过高")
                .contains("补充要求：希望下周再下单");
        assertThat(updated).contains("用户驳回了这次修改，销售单 SO001 保持原样。")
                .contains("原因：价格过高");
    }

    @Test
    void draftFailedTextKeepsRejectedParams() {
        String text = OrderConfirmationTexts.draftText(draft(
                OrderTypeProfile.PURCHASE, "failed", false, "7001", "PO001", "DRAFT",
                draftParams("5", "6", "1"), draftParams("5", "6", "1"), null, List.of(), null,
                "库存不足"), mapper);

        assertThat(text).contains("采购单 PO001 修改未保存：库存不足。")
                .contains("被拒参数：");
    }

    @Test
    void actionSubmittedTextIncludesStateAndArrivalDate() {
        String text = OrderConfirmationTexts.actionText(action(
                OrderTypeProfile.PURCHASE, "submitted", "7001", "PO001", "SUBMITTED",
                null, null, "提交", "预计到货", "2026-12-01", null), mapper);

        assertThat(text).contains("用户同意提交，采购单 PO001 已提交审核。")
                .contains("当前状态为待审核。")
                .contains("预计到货：2026-12-01");
    }

    @Test
    void actionCancelledAndRejectedUseConclusionFirst() {
        String cancelled = OrderConfirmationTexts.actionText(action(
                OrderTypeProfile.SALES, "cancelled", "9001", "SO001", "CANCELLED",
                null, "客户取消需求", "取消", "预计发货", null, null), mapper);
        String rejected = OrderConfirmationTexts.actionText(action(
                OrderTypeProfile.SALES, "rejected", "9001", "SO001", "DRAFT",
                null, "再考虑一下", "提交", "预计发货", null, null), mapper);

        assertThat(cancelled).startsWith("用户同意取消，销售单 SO001 已取消。")
                .contains("原因：客户取消需求");
        assertThat(rejected).startsWith("用户暂不提交，销售单 SO001 保持草稿状态。")
                .contains("原因：再考虑一下");
    }

    @Test
    void actionFailedTextIncludesCurrentOrderParams() {
        Map<String, Object> orderParams = Map.of("salesOrderId", "9001", "status", "DRAFT");
        String text = OrderConfirmationTexts.actionText(action(
                OrderTypeProfile.SALES, "failed", "9001", "SO001", "DRAFT",
                "单据已删除", null, "提交", "预计发货", null, orderParams), mapper);

        assertThat(text).contains("销售单 SO001 提交未通过：单据已删除。")
                .contains("当前单据：")
                .contains("\"salesOrderId\":\"9001\"");
    }

    @Test
    void returnDraftAndActionUseReturnProfileAndRecordType() {
        Map<String, Object> draft = returnParams("2");
        Map<String, Object> finalParams = returnParams("1");
        String text = OrderConfirmationTexts.draftText(draft(
                OrderTypeProfile.SALES_RETURN, "edited", false, "8001", "SR001", "DRAFT",
                draft, finalParams, "改退一件", List.of(), null, null), mapper);

        assertThat(text).contains("销售退货单 SR001 已更新。")
                .contains("修改的字段：items[0].requestedQty");

        Map<String, Object> record = OrderConfirmationTexts.draftRecord(draft(
                OrderTypeProfile.SALES_RETURN, "edited", false, "8001", "SR001", "DRAFT",
                draft, finalParams, "改退一件", List.of(), null, null));

        assertThat(record).containsEntry("returnType", "SALES_RETURN")
                .containsEntry("changedFields", List.of("items[0].requestedQty"));
    }

    @Test
    void editedRecordCarriesChangedFieldsForHistoryCard() {
        Map<String, Object> finalParams = Map.of(
                "customerId", "2",
                "warehouseId", "3",
                "expectedDeliveryDate", "",
                "remark", "",
                "items", List.of(Map.of(
                        "productId", "4",
                        "quantity", new BigDecimal("2"),
                        "unitPrice", new BigDecimal("10.00"),
                        "remark", "")));

        Map<String, Object> record = OrderConfirmationTexts.draftRecord(draft(
                OrderTypeProfile.SALES, "edited", false, "9001", "SO001", "DRAFT",
                draftParams("2", "3", "1"), finalParams, "客户改数量", List.of(), null, null));

        assertThat(record).containsEntry("status", "edited")
                .containsEntry("orderId", "9001")
                .containsEntry("orderNo", "SO001")
                .containsEntry("changedFields", List.of("items[0].quantity"));
    }

    @Test
    void draftTextRejectsMissingFinalParamsWhenEdited() {
        assertThatThrownBy(() -> OrderConfirmationTexts.draftText(draft(
                OrderTypeProfile.SALES, "edited", false, "9001", "SO001", "DRAFT",
                draftParams("2", "3", "1"), null, null, List.of(), null, null), mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("最终参数");
    }

    private OrderDraftConfirmation draft(OrderTypeProfile profile, String status, boolean create,
                                         String orderId, String orderNo, String orderStatus,
                                         Map<String, Object> draft, Map<String, Object> finalParams,
                                         String userNote, List<String> reasons, String suggestion,
                                         String error) {
        return new OrderDraftConfirmation(profile, status, create, orderId, orderNo, orderStatus,
                draft, finalParams, userNote, reasons, suggestion, error);
    }

    private OrderActionConfirmation action(OrderTypeProfile profile, String status, String orderId,
                                           String orderNo, String orderStatus, String error, String reason,
                                           String actionLabel, String extraLabel, String extraValue,
                                           Map<String, Object> orderParams) {
        return new OrderActionConfirmation(profile, status, orderId, orderNo, orderStatus, error,
                reason, actionLabel, extraLabel, extraValue, orderParams);
    }

    private Map<String, Object> returnParams(String requestedQty) {
        return Map.of(
                "sourceOrderId", "10",
                "warehouseId", "20",
                "expectedExecutionDate", "2026-12-01",
                "handlingType", "REFUND",
                "reasonCode", "QUALITY_ISSUE",
                "returnReason", "质量问题",
                "remark", "",
                "items", List.of(Map.of(
                        "sourceOrderItemId", "30",
                        "requestedQty", new BigDecimal(requestedQty),
                        "remark", "")));
    }

    private Map<String, Object> draftParams(String customerId, String warehouseId, String quantity) {
        return Map.of(
                "customerId", customerId,
                "warehouseId", warehouseId,
                "expectedDeliveryDate", "",
                "remark", "",
                "items", List.of(Map.of(
                        "productId", "4",
                        "quantity", new BigDecimal(quantity),
                        "unitPrice", new BigDecimal("10.00"),
                        "remark", "")));
    }
}