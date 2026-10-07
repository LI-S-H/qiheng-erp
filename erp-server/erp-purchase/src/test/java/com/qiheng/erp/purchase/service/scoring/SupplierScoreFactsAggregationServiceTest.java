package com.qiheng.erp.purchase.service.scoring;

import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrderItem;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.mapper.PurchaseOrderItemMapper;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBillItem;
import com.qiheng.erp.warehouse.mapper.InboundBillItemMapper;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupplierScoreFactsAggregationServiceTest {
    private final PurchaseOrderMapper orders = mock(PurchaseOrderMapper.class);
    private final PurchaseOrderItemMapper items = mock(PurchaseOrderItemMapper.class);
    private final InboundBillMapper bills = mock(InboundBillMapper.class);
    private final InboundBillItemMapper batches = mock(InboundBillItemMapper.class);
    private final SupplierProductMapper products = mock(SupplierProductMapper.class);
    private final SupplierScoreFactsAggregationService service = new SupplierScoreFactsAggregationService(orders, items, bills, batches, products);
    private final LocalDate date = LocalDate.of(2026, 9, 26);
    private PurchaseOrder order;
    private PurchaseOrderItem item;

    @BeforeEach
    void setup() {
        for (Class<?> entity : List.of(PurchaseOrder.class, PurchaseOrderItem.class, SupplierProduct.class, InboundBill.class, InboundBillItem.class)) {
            com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                    new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), "test"), entity);
        }
        order = new PurchaseOrder(); order.setId(1L); order.setSupplierId(2L); order.setStatus("PARTIAL_INBOUND");
        order.setExpectedArrivalDate(date.minusDays(9)); order.setPurchaseNo("TEST");
        item = new PurchaseOrderItem(); item.setId(3L); item.setPurchaseOrderId(1L); item.setSupplierProductId(4L);
        item.setQuantity(1000L); item.setInboundQty(700L); item.setTotalAmount(1000L);
        SupplierProduct product = new SupplierProduct(); product.setId(4L); product.setSupplierId(2L);
        when(items.selectList(any())).thenReturn(List.of(item));
        when(products.selectScoringOwners(any())).thenReturn(List.of(product));
    }

    @Test
    void deliveryMustCountLineOnceAndPenalizeEachBatchAndRemainder() {
        when(orders.selectList(any())).thenReturn(List.of(order));
        when(bills.selectList(any())).thenReturn(List.of(bill(10L, date.minusDays(9)), bill(11L, date.minusDays(5))));
        when(batches.selectList(any())).thenReturn(List.of(batch(100L, 10L, 400L, 400L, 0L, 100L), batch(101L, 11L, 300L, 300L, 0L, 100L)));
        var result = service.queryDeliveryFacts(2L, date);
        assertEquals(1000, result.dueAmountCents());
        assertEquals(375, result.penaltyAmountCents());
        assertEquals(1, result.validOrders());
    }

    @Test
    void noConfirmedReceiptStillCountsFullOverdueAmount() {
        item.setInboundQty(0L);
        when(orders.selectList(any())).thenReturn(List.of(order));
        when(bills.selectList(any())).thenReturn(List.of());
        var result = service.queryDeliveryFacts(2L, date);
        assertEquals(1000, result.dueAmountCents()); assertEquals(750, result.penaltyAmountCents());
        verifyNoInteractions(batches);
    }

    @Test
    void qualityUsesAllInboundBatchesAndTheirPriceNotPurchaseTotal() {
        order.setStatus("INBOUND_DONE"); order.setFullyReceivedAt(date.atTime(10, 0)); item.setInboundQty(1000L);
        item.setTotalAmount(999999L); // 故意与入库金额不同，证明质量不依赖采购汇总金额。
        when(orders.selectList(any())).thenReturn(List.of(order), List.of());
        when(bills.selectList(any())).thenReturn(List.of(bill(10L, date.minusDays(190)), bill(11L, date)));
        when(batches.selectList(any())).thenReturn(List.of(batch(100L, 10L, 400L, 400L, 0L, 1000L), batch(101L, 11L, 600L, 300L, 300L, 2000L)));
        var result = service.queryQualityFacts(2L, date);
        assertEquals(BigInteger.valueOf(1000000), result.qualifiedAmountRaw());
        assertEquals(BigInteger.valueOf(600000), result.defectiveAmountRaw());
        assertEquals(6250, new QualityScoreCalculator().calculateAmounts(result.qualifiedAmountRaw(), result.defectiveAmountRaw()));
        assertEquals(1, result.validOrders());
        assertEquals(java.util.Set.of(1L), result.includedTodayOrderIds());
    }

    @Test
    void targetedQualityMustReadWholeOrderButAggregateOnlyRequestedProducts() {
        order.setStatus("INBOUND_DONE"); order.setFullyReceivedAt(date.atTime(10, 0)); item.setInboundQty(1000L);
        PurchaseOrderItem other = new PurchaseOrderItem(); other.setId(5L); other.setPurchaseOrderId(1L);
        other.setSupplierProductId(6L); other.setQuantity(100L); other.setInboundQty(100L); other.setTotalAmount(999999L);
        SupplierProduct otherProduct = new SupplierProduct().setId(6L).setSupplierId(2L);
        when(items.selectList(any())).thenReturn(List.of(item, other));
        when(products.selectScoringOwners(any())).thenReturn(List.of(new SupplierProduct().setId(4L).setSupplierId(2L), otherProduct));
        when(orders.selectQualityOrdersForProducts(eq(2L), any(), any(), eq(Set.of(4L)), anyLong(), eq(200), eq(false)))
                .thenReturn(List.of(order));
        when(bills.selectList(any())).thenReturn(List.of(bill(10L, date.minusDays(190)), bill(11L, date)));
        when(batches.selectList(any())).thenReturn(List.of(batch(100L, 10L, 400L, 400L, 0L, 1000L),
                batch(101L, 11L, 600L, 300L, 300L, 2000L), batch(102L, 11L, 100L, 50L, 50L, 10000L).setSourceItemId(5L)));
        var result = service.queryQualityFacts(2L, date, Set.of(4L));
        assertEquals(Set.of(4L), result.productAmounts().keySet());
        assertEquals(BigInteger.valueOf(1000000), result.qualifiedAmountRaw());
        assertEquals(BigInteger.valueOf(600000), result.defectiveAmountRaw());
        assertEquals(1, result.validOrders());
        verify(orders, never()).selectList(any());
        verify(orders).selectQualityOrdersForProducts(2L, date.minusDays(179).atStartOfDay(),
                date.plusDays(1).atStartOfDay(), Set.of(4L), 0L, 200, false);
    }

    @Test
    void unrelatedBadLineMustInvalidateWholeTargetedQualityOrder() {
        order.setStatus("INBOUND_DONE"); order.setFullyReceivedAt(date.atTime(10, 0)); item.setInboundQty(1000L);
        PurchaseOrderItem bad = new PurchaseOrderItem(); bad.setId(5L); bad.setPurchaseOrderId(1L);
        bad.setSupplierProductId(6L); bad.setQuantity(100L); bad.setInboundQty(100L);
        when(items.selectList(any())).thenReturn(List.of(item, bad));
        when(products.selectScoringOwners(any())).thenReturn(List.of(new SupplierProduct().setId(4L).setSupplierId(2L),
                new SupplierProduct().setId(6L).setSupplierId(2L)));
        when(orders.selectQualityOrdersForProducts(anyLong(), any(), any(), any(), anyLong(), anyInt(), eq(false)))
                .thenReturn(List.of(order));
        when(bills.selectList(any())).thenReturn(List.of(bill(10L, date)));
        when(batches.selectList(any())).thenReturn(List.of(batch(100L, 10L, 1000L, 1000L, 0L, 1000L)));
        var result = service.queryQualityFacts(2L, date, Set.of(4L));
        assertTrue(result.productAmounts().isEmpty());
        assertEquals(0, result.validOrders()); assertEquals(1, result.skippedOrders());
    }

    @Test
    void targetedEmptyAndMissingSamplesMustNotInventZeroProductAmounts() {
        var empty = service.queryQualityFacts(2L, date, Set.of());
        assertTrue(empty.productAmounts().isEmpty()); verifyNoInteractions(orders, items, bills, batches, products);
        var absent = service.queryQualityFacts(2L, date, Set.of(999L));
        assertTrue(absent.productAmounts().isEmpty());
        verifyNoInteractions(items, bills, batches, products);
    }

    @Test
    void targetedProductBatchesMustDeduplicateOrdersAndBoundParameterCount() {
        order.setStatus("INBOUND_DONE"); order.setFullyReceivedAt(date.atTime(10, 0)); item.setInboundQty(1000L);
        Set<Long> targetIds = new java.util.LinkedHashSet<>();
        for (long id = 4; id <= 204; id++) targetIds.add(id);
        when(orders.selectQualityOrdersForProducts(anyLong(), any(), any(), any(), anyLong(), anyInt(), eq(false)))
                .thenReturn(List.of(order));
        when(bills.selectList(any())).thenReturn(List.of(bill(10L, date)));
        when(batches.selectList(any())).thenReturn(List.of(batch(100L, 10L, 1000L, 1000L, 0L, 100L)));
        var result = service.queryQualityFacts(2L, date, targetIds);
        assertEquals(1, result.validOrders()); assertEquals(BigInteger.valueOf(100000), result.qualifiedAmountRaw());
        verify(items, times(1)).selectList(any());
        var capture = org.mockito.ArgumentCaptor.forClass(Set.class);
        verify(orders, times(4)).selectQualityOrdersForProducts(anyLong(), any(), any(), capture.capture(),
                anyLong(), eq(200), anyBoolean());
        assertTrue(capture.getAllValues().stream().allMatch(ids -> ids.size() <= 200));
    }

    @Test
    void completedOrderBatchDeduplicatesAndKeepsFullReceiptContributions() {
        order.setStatus("INBOUND_DONE"); order.setFullyReceivedAt(date.atTime(10, 0)); item.setInboundQty(1000L);
        PurchaseOrder second = new PurchaseOrder().setId(2L).setSupplierId(2L).setStatus("INBOUND_DONE")
                .setFullyReceivedAt(date.atTime(11, 0));
        PurchaseOrderItem secondItem = new PurchaseOrderItem(); secondItem.setId(7L); secondItem.setPurchaseOrderId(2L);
        secondItem.setSupplierProductId(4L); secondItem.setQuantity(500L); secondItem.setInboundQty(500L);
        when(orders.selectByIds(any())).thenReturn(List.of(order, second));
        when(items.selectList(any())).thenReturn(List.of(item, secondItem));
        when(bills.selectList(any())).thenReturn(List.of(bill(10L, date.minusDays(190)), bill(11L, date),
                bill(12L, date).setSourceId(2L)));
        when(batches.selectList(any())).thenReturn(List.of(batch(100L, 10L, 400L, 400L, 0L, 1000L),
                batch(101L, 11L, 600L, 300L, 300L, 2000L), batch(102L, 12L, 500L, 250L, 250L, 100L).setSourceItemId(7L)));
        var result = service.queryQualityContributions(2L, List.of(1L, 2L, 1L), date);
        assertEquals(Set.of(1L, 2L), result.orders().keySet()); assertEquals(Set.of(4L), result.supplierProductIds());
        assertEquals(BigInteger.valueOf(1000000), result.orders().get(1L).qualifiedAmountRaw());
        assertEquals(BigInteger.valueOf(600000), result.orders().get(1L).defectiveAmountRaw());
        assertEquals(BigInteger.valueOf(25000), result.orders().get(2L).qualifiedAmountRaw());
        assertEquals(Set.of(1L), result.orders().get(1L).includedTodayOrderIds());
        verify(orders).selectByIds(List.of(1L, 2L));
    }

    @Test
    void completedOrderBatchSkipsInvalidOrderButRetainsAffectedProducts() {
        order.setStatus("INBOUND_DONE"); order.setFullyReceivedAt(date.atTime(10, 0)); item.setInboundQty(1000L);
        when(orders.selectByIds(any())).thenReturn(List.of(order));
        when(bills.selectList(any())).thenReturn(List.of());
        var result = service.queryQualityContributions(2L, List.of(1L), date);
        assertEquals(Set.of(4L), result.supplierProductIds());
        assertEquals(1, result.orders().get(1L).skippedOrders());
        assertEquals(BigInteger.ZERO, result.orders().get(1L).qualifiedAmountRaw());
    }

    @Test
    void completedOrderBatchRejectsMissingOrForeignSourcesWithoutFallback() {
        when(orders.selectByIds(any())).thenReturn(List.of());
        assertThrows(com.qiheng.erp.common.exception.BizException.class,
                () -> service.queryQualityContributions(2L, List.of(1L), date));
        when(orders.selectByIds(any())).thenReturn(List.of(order));
        assertThrows(com.qiheng.erp.common.exception.BizException.class,
                () -> service.queryQualityContributions(999L, List.of(1L), date));
        verifyNoInteractions(bills, batches);
    }

    @Test
    void expectedDayFallbackMustNotMarkOuterTransactionRollbackOnly() throws NoSuchMethodException {
        var method = SupplierScoreFactsAggregationService.class.getMethod("queryQualityContributions",
                Long.class, java.util.Collection.class, LocalDate.class);
        var transaction = method.getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertNotNull(transaction); assertTrue(transaction.readOnly());
        assertArrayEquals(new Class<?>[]{IllegalArgumentException.class}, transaction.noRollbackFor());
        assertFalse(java.util.Arrays.stream(transaction.noRollbackFor())
                .anyMatch(type -> type.isAssignableFrom(com.qiheng.erp.common.exception.BizException.class)));
    }

    @Test
    void completedOrderBatchRejectsYesterdayAndBoundsOrderIdBatches() {
        order.setStatus("INBOUND_DONE"); order.setFullyReceivedAt(date.minusDays(1).atTime(10, 0));
        when(orders.selectByIds(any())).thenReturn(List.of(order));
        assertThrows(IllegalArgumentException.class, () -> service.queryQualityContributions(2L, List.of(1L), date));
        List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 201).boxed().toList();
        when(orders.selectByIds(any())).thenAnswer(invocation -> {
            java.util.Collection<Long> selected = invocation.getArgument(0);
            assertTrue(selected.size() <= 200);
            return selected.stream().map(id -> new PurchaseOrder().setId(id).setSupplierId(2L)
                    .setStatus("INBOUND_DONE").setFullyReceivedAt(date.atTime(10, 0))).toList();
        });
        when(items.selectList(any())).thenReturn(List.of()); when(bills.selectList(any())).thenReturn(List.of());
        var result = service.queryQualityContributions(2L, ids, date);
        assertEquals(201, result.orders().size());
        assertEquals(201, result.orders().values().stream().mapToLong(SupplierScoreFactsAggregationService.QualityFacts::skippedOrders).sum());
    }

    @Test
    void targetedQualitySqlUsesExistsAndCursorWithoutFixedSampleCap() {
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        configuration.addMapper(PurchaseOrderMapper.class);
        var statement = configuration.getMappedStatement(PurchaseOrderMapper.class.getName() + ".selectQualityOrdersForProducts");
        var sql = statement.getBoundSql(Map.of("supplierId", 2L, "windowStart", date.minusDays(179).atStartOfDay(),
                "windowEnd", date.plusDays(1).atStartOfDay(), "supplierProductIds", Set.of(4L),
                "cursor", 200L, "pageSize", 200, "missingCompletionTime", false)).getSql();
        assertTrue(sql.contains("EXISTS")); assertTrue(sql.contains("po.deleted = 0"));
        assertFalse(sql.contains("item.deleted")); // 采购明细没有逻辑删除列。
        assertTrue(sql.contains("po.fully_received_at >=")); assertTrue(sql.contains("po.fully_received_at <"));
        assertTrue(sql.contains("po.id >")); assertTrue(sql.contains("ORDER BY po.id ASC LIMIT ?"));
        assertFalse(sql.contains("30000")); assertFalse(sql.contains("confirmed_at"));
    }

    @Test
    void quantityMismatchSkipsWholeOrderInsteadOfFalseOverdue() {
        when(orders.selectList(any())).thenReturn(List.of(order));
        when(bills.selectList(any())).thenReturn(List.of(bill(10L, date)));
        when(batches.selectList(any())).thenReturn(List.of(batch(100L, 10L, 400L, 400L, 0L, 100L)));
        var result = service.queryDeliveryFacts(2L, date);
        assertEquals(0, result.dueAmountCents()); assertEquals(1, result.skippedOrders());
    }

    @Test
    void deliveryContinuesPastFirstPageWithoutTruncatingSamples() {
        List<PurchaseOrder> firstPage = new java.util.ArrayList<>();
        List<PurchaseOrderItem> allItems = new java.util.ArrayList<>();
        for (long id = 1; id <= 201; id++) {
            PurchaseOrder po = new PurchaseOrder(); po.setId(id); po.setSupplierId(2L);
            po.setStatus("APPROVED"); po.setExpectedArrivalDate(date.minusDays(9));
            if (id <= 200) firstPage.add(po); else when(orders.selectList(any())).thenReturn(firstPage, List.of(po));
            PurchaseOrderItem line = new PurchaseOrderItem(); line.setId(1000 + id); line.setPurchaseOrderId(id);
            line.setSupplierProductId(4L); line.setQuantity(100L); line.setInboundQty(0L); line.setTotalAmount(100L);
            allItems.add(line);
        }
        when(items.selectList(any())).thenReturn(allItems); when(bills.selectList(any())).thenReturn(List.of());
        var result = service.queryDeliveryFacts(2L, date);
        assertEquals(201, result.validOrders()); assertEquals(20100, result.dueAmountCents());
        assertEquals(15075, result.penaltyAmountCents()); verify(orders, times(2)).selectList(any());
    }

    @Test
    void deliveryQueryUsesExpectedDateWindowAndKeepsCompletedOrders() {
        when(orders.selectList(any())).thenReturn(List.of());
        service.queryDeliveryFacts(2L, date);
        var capture = org.mockito.ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        verify(orders).selectList(capture.capture());
        String sql = capture.getValue().getSqlSegment();
        assertTrue(sql.contains("expected_arrival_date")); assertFalse(sql.contains("approved_at"));
        var params = capture.getValue().getParamNameValuePairs();
        assertTrue(params.containsValue(date.minusDays(180))); assertTrue(params.containsValue(date));
        assertTrue(params.values().stream().anyMatch(value ->
                value != null && value.toString().contains("INBOUND_DONE")));
    }

    @Test
    void subCentPenaltyKeepsCorrectScoreAndDoesNotReadCurrentProduct() {
        item.setInboundQty(0L); item.setTotalAmount(1L); order.setExpectedArrivalDate(date.minusDays(1));
        when(orders.selectList(any())).thenReturn(List.of(order)); when(bills.selectList(any())).thenReturn(List.of());
        var result = service.queryDeliveryFacts(2L, date);
        assertEquals(0, new java.math.BigDecimal("0.25").compareTo(result.penaltyAmountRaw()));
        assertEquals(7500, new DeliveryScoreCalculator().calculateAmounts(result.dueAmountCents(), result.penaltyAmountRaw()));
        verify(products, never()).selectScoringOwners(any()); verify(products, never()).selectList(any());
    }

    @Test
    void halfCentPartialReceiptCannotEraseOverdueRemainder() {
        item.setQuantity(100L); item.setInboundQty(50L); item.setTotalAmount(1L);
        order.setExpectedArrivalDate(date.minusDays(4));
        when(orders.selectList(any())).thenReturn(List.of(order));
        when(bills.selectList(any())).thenReturn(List.of(bill(10L, date.minusDays(4))));
        when(batches.selectList(any())).thenReturn(List.of(batch(100L, 10L, 50L, 50L, 0L, 1L)));
        var result = service.queryDeliveryFacts(2L, date);
        assertEquals(0, new java.math.BigDecimal("0.25").compareTo(result.penaltyAmountRaw()));
        assertEquals(7500, new DeliveryScoreCalculator().calculateAmounts(result.dueAmountCents(), result.penaltyAmountRaw()));
    }

    private InboundBill bill(long id, LocalDate confirmed) {
        InboundBill bill = new InboundBill(); bill.setId(id); bill.setSourceId(1L); bill.setConfirmedAt(confirmed.atTime(12, 0)); return bill;
    }
    private InboundBillItem batch(long id, long billId, long current, long qualified, long defective, long price) {
        return new InboundBillItem().setId(id).setInboundBillId(billId).setSourceItemId(3L)
                .setCurrentQty(current).setQualifiedQty(qualified).setDefectiveQty(defective).setUnitPrice(price);
    }
}
