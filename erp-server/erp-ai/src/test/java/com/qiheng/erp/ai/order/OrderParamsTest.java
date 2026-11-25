package com.qiheng.erp.ai.order;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证单据参数清洗与变化比较：只保留业务字段，数字按数值比较。
 *
 * @author Li
 * @since 2026-11-25
 */
class OrderParamsTest {

    @Test
    void sanitizeKeepsBusinessFieldsAndDropsDerivedFields() {
        Map<String, Object> params = Map.of(
                "customerId", "2",
                "warehouseId", "3",
                "expectedDeliveryDate", "2026-12-01",
                "remark", "加急",
                "customerName", "测试客户",
                "totalAmount", "100.00",
                "items", List.of(Map.of(
                        "productId", "4",
                        "quantity", new BigDecimal("10"),
                        "unitPrice", new BigDecimal("9.90"),
                        "remark", "整箱",
                        "productName", "测试产品")));

        Map<String, Object> actual = OrderParams.sanitize(params, OrderTypeProfile.SALES);

        assertThat(actual).containsEntry("customerId", "2")
                .containsEntry("warehouseId", "3")
                .containsEntry("expectedDeliveryDate", "2026-12-01")
                .containsEntry("remark", "加急")
                .doesNotContainKeys("customerName", "totalAmount");
        List<?> items = (List<?>) actual.get("items");
        assertThat(items).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) items.getFirst();
        assertThat(item).containsEntry("productId", "4")
                .containsEntry("quantity", new BigDecimal("10"))
                .containsEntry("unitPrice", new BigDecimal("9.90"))
                .containsEntry("remark", "整箱")
                .doesNotContainKey("productName");
    }

    @Test
    void sanitizeReturnsNullWhenItemsMissingOrEmpty() {
        Map<String, Object> withoutItems = Map.of("customerId", "2", "warehouseId", "3");
        Map<String, Object> emptyItems = Map.of("customerId", "2", "warehouseId", "3", "items", List.of());

        assertThat(OrderParams.sanitize(withoutItems, OrderTypeProfile.SALES)).isNull();
        assertThat(OrderParams.sanitize(emptyItems, OrderTypeProfile.SALES)).isNull();
    }

    @Test
    void diffIgnoresNumericScaleAndReportsChangedPaths() {
        Map<String, Object> draft = Map.of(
                "customerId", "2",
                "warehouseId", "3",
                "items", List.of(Map.of(
                        "productId", "4",
                        "quantity", new BigDecimal("100"),
                        "unitPrice", new BigDecimal("10.00"))));
        Map<String, Object> sameValues = Map.of(
                "customerId", "2",
                "warehouseId", "3",
                "items", List.of(Map.of(
                        "productId", "4",
                        "quantity", new BigDecimal("100.00"),
                        "unitPrice", new BigDecimal("10"))));

        assertThat(OrderParams.diff(draft, sameValues, OrderTypeProfile.SALES)).isEmpty();

        Map<String, Object> changed = Map.of(
                "customerId", "9",
                "warehouseId", "3",
                "items", List.of(Map.of(
                        "productId", "4",
                        "quantity", new BigDecimal("101"),
                        "unitPrice", new BigDecimal("10.00"))));

        assertThat(OrderParams.diff(draft, changed, OrderTypeProfile.SALES))
                .containsExactly("customerId", "items[0].quantity");
    }
}