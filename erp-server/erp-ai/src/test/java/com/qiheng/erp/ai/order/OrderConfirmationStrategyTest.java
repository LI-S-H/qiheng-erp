package com.qiheng.erp.ai.order;

import com.qiheng.erp.ai.action.PurchaseOrderActionReceipt;
import com.qiheng.erp.ai.draft.PurchaseDraftReceipt;
import com.qiheng.erp.ai.sales.SalesOrderActionReceipt;
import com.qiheng.erp.ai.sales.SalesOrderDraftReceipt;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证销售/采购回执到统一确认结构的映射，以及类型不匹配的拒绝。
 *
 * @author Li
 * @since 2026-11-25
 */
class OrderConfirmationStrategyTest {

    private final SalesOrderConfirmationStrategy sales = new SalesOrderConfirmationStrategy();
    private final PurchaseOrderConfirmationStrategy purchase = new PurchaseOrderConfirmationStrategy();

    @Test
    void salesDraftReceiptMapsToCommonConfirmation() {
        Map<String, Object> draft = Map.of("customerId", "2", "warehouseId", "3", "items", List.of());
        SalesOrderDraftReceipt receipt = new SalesOrderDraftReceipt(
                "created", "SO001", "9001", "DRAFT", null, null, null, List.of(), null);

        OrderDraftConfirmation confirmation = sales.draftConfirmation(receipt, "CREATE", draft);

        assertThat(confirmation.profile()).isEqualTo(OrderTypeProfile.SALES);
        assertThat(confirmation.create()).isTrue();
        assertThat(confirmation.status()).isEqualTo("created");
        assertThat(confirmation.orderId()).isEqualTo("9001");
        assertThat(confirmation.orderNo()).isEqualTo("SO001");
        assertThat(confirmation.draft()).isEqualTo(draft);
    }

    @Test
    void salesActionReceiptMapsDeliveryDateAndActionLabel() {
        SalesOrderActionReceipt receipt = new SalesOrderActionReceipt(
                "submitted", "9001", "SO001", "SUBMITTED", null, null, "2026-12-01", null);

        OrderActionConfirmation confirmation = sales.actionConfirmation(receipt, "SUBMIT");

        assertThat(confirmation.profile()).isEqualTo(OrderTypeProfile.SALES);
        assertThat(confirmation.actionLabel()).isEqualTo("提交");
        assertThat(confirmation.extraLabel()).isEqualTo("预计发货");
        assertThat(confirmation.extraValue()).isEqualTo("2026-12-01");
    }

    @Test
    void purchaseDraftReceiptMapsToPurchaseProfile() {
        PurchaseDraftReceipt receipt = new PurchaseDraftReceipt(
                "edited", "PO001", "7001", "DRAFT", null, Map.of(), "改数量", List.of(), null);

        OrderDraftConfirmation confirmation = purchase.draftConfirmation(receipt, "UPDATE",
                Map.of("supplierId", "5"));

        assertThat(confirmation.profile()).isEqualTo(OrderTypeProfile.PURCHASE);
        assertThat(confirmation.create()).isFalse();
        assertThat(confirmation.status()).isEqualTo("edited");
        assertThat(confirmation.orderId()).isEqualTo("7001");
        assertThat(confirmation.userNote()).isEqualTo("改数量");
    }

    @Test
    void purchaseActionReceiptMapsArrivalDate() {
        PurchaseOrderActionReceipt receipt = new PurchaseOrderActionReceipt(
                "submitted", "7001", "PO001", "SUBMITTED", null, null, "2026-12-02", null);

        OrderActionConfirmation confirmation = purchase.actionConfirmation(receipt, "SUBMIT");

        assertThat(confirmation.profile()).isEqualTo(OrderTypeProfile.PURCHASE);
        assertThat(confirmation.extraLabel()).isEqualTo("预计到货");
        assertThat(confirmation.extraValue()).isEqualTo("2026-12-02");
    }

    @Test
    void mismatchedReceiptTypeIsRejected() {
        PurchaseDraftReceipt receipt = new PurchaseDraftReceipt(
                "created", "PO001", "7001", "DRAFT", null, null, null, List.of(), null);

        assertThatThrownBy(() -> sales.draftConfirmation(receipt, "CREATE", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("回执类型");
    }
}