package com.qiheng.erp.ai.returnorder;

import com.qiheng.erp.returnorder.domain.vo.ReturnOrderItemVo;
import com.qiheng.erp.returnorder.domain.vo.ReturnableSourceDetailVo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退货草稿预检：只校验结构与已查得的可退事实，不写库、不加锁。
 *
 * @author Li
 * @since 2026-11-24
 */
class ReturnDraftPrecheckTest {

    private final ReturnDraftPrecheck precheck = new ReturnDraftPrecheck();

    @Test
    void acceptsValidDraft() {
        String error = precheck.check(draft("SALES_RETURN", "501", "2", "12.00",
                LocalDate.now().plusDays(3).toString(), "DAMAGED", "包装破损"),
                source("SALES_RETURN", "OUTBOUND_DONE", "501", "2", "12.00", 0));

        assertThat(error).isNull();
    }

    @Test
    void rejectsWarehouseMismatch() {
        String error = precheck.check(draft("SALES_RETURN", "502", "2", "12.00",
                null, "DAMAGED", "包装破损"),
                source("SALES_RETURN", "OUTBOUND_DONE", "501", "2", "12.00", 0));

        assertThat(error).contains("仓库").contains("原订单仓库");
    }

    @Test
    void rejectsQuantityOverAvailable() {
        String error = precheck.check(draft("SALES_RETURN", "501", "2", "30.00",
                null, "DAMAGED", "包装破损"),
                source("SALES_RETURN", "OUTBOUND_DONE", "501", "2", "12.00", 0));

        assertThat(error).contains("超过可退").contains("12");
    }

    @Test
    void rejectsItemNotBelongingToSource() {
        String error = precheck.check(draft("SALES_RETURN", "501", "9", "1.00",
                null, "DAMAGED", "包装破损"),
                source("SALES_RETURN", "OUTBOUND_DONE", "501", "2", "12.00", 0));

        assertThat(error).contains("不属于来源订单");
    }

    @Test
    void rejectsDuplicateSourceItems() {
        ReturnOrderDraftRequest request = new ReturnOrderDraftRequest("SALES_RETURN", "1001", "501",
                null, null, "DAMAGED", "包装破损", null,
                List.of(new ReturnOrderDraftRequest.Item("2", new BigDecimal("1"), null),
                        new ReturnOrderDraftRequest.Item("2", new BigDecimal("2"), null)));

        String error = precheck.check(request, source("SALES_RETURN", "OUTBOUND_DONE", "501", "2", "12.00", 0));

        assertThat(error).contains("重复");
    }

    @Test
    void rejectsQuantityExceedingPrecision() {
        String error = precheck.check(draft("SALES_RETURN", "501", "2", "1.25",
                null, "DAMAGED", "包装破损"),
                source("SALES_RETURN", "OUTBOUND_DONE", "501", "2", "12.00", 0));

        assertThat(error).contains("位小数");
    }

    @Test
    void rejectsPastExecutionDate() {
        String error = precheck.check(draft("SALES_RETURN", "501", "2", "1.00",
                LocalDate.now().minusDays(1).toString(), "DAMAGED", "包装破损"),
                source("SALES_RETURN", "OUTBOUND_DONE", "501", "2", "12.00", 0));

        assertThat(error).contains("不得早于当天");
    }

    @Test
    void rejectsOtherReasonWithoutExplanation() {
        String error = precheck.check(draft("SALES_RETURN", "501", "2", "1.00",
                null, "OTHER", " "),
                source("SALES_RETURN", "OUTBOUND_DONE", "501", "2", "12.00", 0));

        assertThat(error).contains("其他原因").contains("说明");
    }

    @Test
    void rejectsInvalidReasonCode() {
        String error = precheck.check(draft("SALES_RETURN", "501", "2", "1.00",
                null, "CUSTOMER_RETURN", "包装破损"),
                source("SALES_RETURN", "OUTBOUND_DONE", "501", "2", "12.00", 0));

        assertThat(error).contains("退货原因");
    }

    @Test
    void rejectsSourceStatusNotReturnable() {
        String error = precheck.check(draft("SALES_RETURN", "501", "2", "1.00",
                null, "DAMAGED", "包装破损"),
                source("SALES_RETURN", "DRAFT", "501", "2", "12.00", 0));

        assertThat(error).contains("状态").contains("不允许退货");
    }

    @Test
    void rejectsReturnTypeMismatch() {
        String error = precheck.check(draft("PURCHASE_RETURN", "501", "2", "1.00",
                null, "DAMAGED", "包装破损"),
                source("SALES_RETURN", "OUTBOUND_DONE", "501", "2", "12.00", 0));

        assertThat(error).contains("退货类型").contains("来源单");
    }

    private ReturnOrderDraftRequest draft(String returnType, String warehouseId, String sourceItemId,
                                          String qty, String executionDate, String reasonCode, String reasonText) {
        return new ReturnOrderDraftRequest(returnType, "1001", warehouseId, executionDate,
                "REFUND", reasonCode, reasonText, null,
                List.of(new ReturnOrderDraftRequest.Item(sourceItemId, new BigDecimal(qty), null)));
    }

    private ReturnableSourceDetailVo source(String returnType, String status, String warehouseId,
                                            String sourceItemId, String availableQty, int precision) {
        ReturnOrderItemVo item = new ReturnOrderItemVo();
        item.setSourceOrderItemId(Long.parseLong(sourceItemId));
        item.setProductId(101L);
        item.setProductCode("P-101");
        item.setProductName("测试产品");
        item.setUnitName("件");
        item.setQuantityPrecision(precision);
        item.setSourceFulfilledQty(new BigDecimal(availableQty));
        item.setOccupiedQty(BigDecimal.ZERO);
        item.setStockAvailableQty(BigDecimal.ZERO);
        item.setAvailableReturnQty(new BigDecimal(availableQty));

        ReturnableSourceDetailVo source = new ReturnableSourceDetailVo();
        source.setReturnType(returnType);
        source.setSourceOrderId(1_001L);
        source.setSourceOrderNo("SRC-1001");
        source.setSourceOrderStatus(status);
        source.setPartyId(301L);
        source.setPartyCode("PARTY-301");
        source.setPartyName("往来单位");
        source.setWarehouseId(Long.parseLong(warehouseId));
        source.setWarehouseName("主仓");
        source.setItems(List.of(item));
        return source;
    }
}