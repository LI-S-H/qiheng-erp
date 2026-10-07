package com.qiheng.erp.purchase.service.scoring;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.domain.supplierscore.dto.*;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.purchase.service.ISupplierScoreChangeLogService;
import com.qiheng.erp.purchase.service.impl.SupplierScoreRecalculateServiceImpl;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.purchase.domain.supplierscore.enums.OperatorType;
import com.qiheng.erp.purchase.domain.supplierscore.enums.TriggerType;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.mapper.ProductMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.ArgumentCaptor;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupplierFactsRecalculationTest {
    private final SupplierMapper suppliers = mock(SupplierMapper.class);
    private final SupplierProductMapper products = mock(SupplierProductMapper.class);
    private final ScoreFactsQueryService facts = mock(ScoreFactsQueryService.class);
    private final SupplierScoreFactsAggregationService aggregation = mock(SupplierScoreFactsAggregationService.class);
    private final ProductMapper referenceProducts = mock(ProductMapper.class);
    private final ISupplierScoreChangeLogService logs = mock(ISupplierScoreChangeLogService.class);
    private final com.qiheng.erp.common.util.BillNoGenerator generator = mock(com.qiheng.erp.common.util.BillNoGenerator.class);
    private final SupplierScoreRecalculateServiceImpl service = new SupplierScoreRecalculateServiceImpl(
            new QualityScoreCalculator(), new DeliveryScoreCalculator(), new PriceScoreCalculator(), new AggregateScoreCalculator(),
            facts, aggregation, logs,
            suppliers, products,
            referenceProducts,
            generator,
            mock(com.qiheng.erp.purchase.mapper.SupplierScoreChangeLogMapper.class));
    private final Supplier supplier = new Supplier();
    private final SupplierProduct product = new SupplierProduct();
    private final LocalDate date = LocalDate.of(2026, 9, 26);

    @BeforeEach
    void setup() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "supplier-test"), Supplier.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "product-test"), SupplierProduct.class);
        supplier.setId(1L); supplier.setVersion(1); supplier.setPriceScore(8000); supplier.setServiceScore(9000);
        supplier.setQualityScore(9000); supplier.setDeliveryScore(9000); supplier.setOverallScore(8800);
        product.setId(2L); product.setSupplierId(1L); product.setProductId(3L); product.setVersion(1);
        product.setStatus(1); product.setQuotedPurchasePrice(1000L); product.setScoreBasisAmount(100L);
        product.setQuoteValidUntil(date.plusDays(1)); product.setPriceScore(8000);
        product.setQualityScore(9000); product.setRecommendScore(8800);
        when(referenceProducts.selectByIds(any())).thenReturn(List.of(
                new Product().setId(3L).setReferencePurchasePrice(new BigDecimal("8.00"))));
        when(suppliers.selectById(1L)).thenReturn(supplier); when(products.selectList(any())).thenReturn(List.of(product));
        when(suppliers.lockByIdForUpdate(1L)).thenReturn(1L);
        when(suppliers.update(isNull(), any(Wrapper.class))).thenReturn(1);
        when(products.update(isNull(), any(Wrapper.class))).thenReturn(1);
    }

    @Test
    void dailyReconciliationGeneratesUnifiedBatchOnlyWhenWritingChanges() {
        when(facts.queryFacts(1L, date)).thenReturn(snapshot(BigInteger.valueOf(80), BigInteger.valueOf(20)));
        when(generator.nextNo(eq("SC"), any())).thenReturn("SC2026092600001");
        service.recalcFactsForSupplier(ScoreRecalcContext.builder().supplierId(1L)
                .triggerType(TriggerType.DAILY_TRIGGER).operatorType(OperatorType.SYSTEM.name()).build(), date);
        ArgumentCaptor<ScoreChangeBatchCommand> command = ArgumentCaptor.forClass(ScoreChangeBatchCommand.class);
        verify(logs).appendBatch(command.capture());
        assertEquals("SC2026092600001", command.getValue().getBatchNo());
        verify(generator, times(1)).nextNo(eq("SC"), any());
    }

    @Test
    void updatesQualityAndRecommendationWithoutOverwritingPriceOrService() {
        FactsSnapshot snapshot = snapshot(BigInteger.valueOf(80), BigInteger.valueOf(20));
        snapshot.setProductQualityAmounts(Map.of(2L, new SupplierScoreFactsAggregationService.QualityAmount(BigInteger.valueOf(80), BigInteger.valueOf(20))));
        when(facts.queryFacts(1L, date)).thenReturn(snapshot);
        ScoreRecalcResult result = recalc();
        var order = inOrder(suppliers, facts);
        order.verify(suppliers).lockByIdForUpdate(1L);
        order.verify(facts).queryFacts(1L, date);
        assertTrue(result.isChanged());
        assertTrue(result.getMetricChanges().stream().anyMatch(c -> Long.valueOf(2L).equals(c.getSupplierProductId()) && Integer.valueOf(8000).equals(c.getMetricScoreAfter())));
        assertTrue(result.getMetricChanges().stream().anyMatch(c -> Integer.valueOf(8400).equals(c.getProductRecommendScoreAfter())));
        ArgumentCaptor<LambdaUpdateWrapper> capture = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(suppliers).update(isNull(), capture.capture());
        assertTrue(capture.getValue().getParamNameValuePairs().values().contains(8000));
        assertFalse(capture.getValue().getSqlSet().contains("service_score"));
    }

    @Test
    void expiredSamplesExplicitlyClearProductQualityAndRecommendation() {
        when(facts.queryFacts(1L, date)).thenReturn(snapshot(BigInteger.ZERO, BigInteger.ZERO));
        assertTrue(recalc().isChanged());
        ArgumentCaptor<LambdaUpdateWrapper> capture = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(products).update(isNull(), capture.capture());
        assertTrue(capture.getValue().getSqlSet().contains("quality_score"));
        assertTrue(capture.getValue().getParamNameValuePairs().values().contains(null));
    }

    @Test
    void unchangedScoresStillRepairNotReadyStatus() {
        supplier.setQualityScore(8000); supplier.setOverallScore(8400); supplier.setScoreStatus("NOT_READY");
        product.setQualityScore(8000); product.setRecommendScore(8400); product.setScoreStatus("NOT_READY");
        FactsSnapshot snapshot = snapshot(BigInteger.valueOf(80), BigInteger.valueOf(20));
        snapshot.setProductQualityAmounts(Map.of(2L, new SupplierScoreFactsAggregationService.QualityAmount(BigInteger.valueOf(80), BigInteger.valueOf(20))));
        when(facts.queryFacts(1L, date)).thenReturn(snapshot);
        assertTrue(recalc().isChanged());
        verify(suppliers).update(isNull(), any(Wrapper.class)); verify(products).update(isNull(), any(Wrapper.class));
    }

    @Test
    void optimisticConflictIsNotSilentlyIgnored() {
        when(facts.queryFacts(1L, date)).thenReturn(snapshot(BigInteger.ZERO, BigInteger.ZERO));
        when(suppliers.update(isNull(), any(Wrapper.class))).thenReturn(0);
        assertThrows(com.qiheng.erp.common.exception.BizException.class, this::recalc);
    }

    @Test
    void inboundSingleOrderUsesCompletedIdsRatherThanLogSource() {
        FactsSnapshot snapshot = snapshot(BigInteger.valueOf(80), BigInteger.valueOf(20));
        snapshot.setProductQualityAmounts(Map.of(2L,
                new SupplierScoreFactsAggregationService.QualityAmount(BigInteger.valueOf(80), BigInteger.valueOf(20))));
        when(facts.queryInboundFacts(1L, date, List.of(99L))).thenReturn(snapshot);

        service.recalcFactsForSupplier(ScoreRecalcContext.builder().supplierId(1L)
                .completedOrderIds(List.of(99L)).relatedSources(List.of(
                        com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeSource.builder()
                                .businessType(com.qiheng.erp.purchase.domain.supplierscore.enums.ScoreSourceBusinessType.PURCHASE_ORDER)
                                .businessId("100").businessNo("PO-100").build())).triggerType(TriggerType.INBOUND_TRIGGER)
                .batchNo("SC-CROSS-DAY").operatorType(OperatorType.SYSTEM.name()).build(), date);

        verify(facts).queryInboundFacts(1L, date, List.of(99L));
        verify(facts, never()).queryFacts(anyLong(), any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    void inboundMissingOrEmptyOrdersRejectsInputRegardlessOfLogSource(List<Long> orderIds) {
        // 同时覆盖有日志来源和无日志来源，缺少计算范围都不能降级或推断为单单。
        for (boolean hasLogSource : List.of(false, true)) {
            BizException error = assertThrows(BizException.class, () -> service.recalcFactsForSupplier(
                    ScoreRecalcContext.builder().supplierId(1L).triggerType(TriggerType.INBOUND_TRIGGER)
                            .completedOrderIds(orderIds).relatedSources(hasLogSource ? List.of(
                                    com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeSource.builder()
                                            .businessType(com.qiheng.erp.purchase.domain.supplierscore.enums.ScoreSourceBusinessType.PURCHASE_ORDER)
                                            .businessId("99").build()) : List.of()).build(), date));
            assertEquals(ErrorCode.PARAM_ERROR.getCode(), error.getCode());
            assertEquals("入库评分重算必须提供非空的完成采购单集合", error.getMessage());
        }
        verifyNoInteractions(facts);
        verify(suppliers, never()).update(isNull(), any(Wrapper.class));
        verify(products, never()).update(isNull(), any(Wrapper.class));
    }

    @Test
    void warmQualityRetainsStoredDeliveryAndDoesNotQueryDeliveryHistory() {
        FactsSnapshot snapshot = snapshot(BigInteger.valueOf(80), BigInteger.valueOf(20));
        snapshot.setNeedDeliveryRecalculation(false);
        snapshot.setFullQualityRebuild(false);
        snapshot.setAffectedSupplierProductIds(java.util.Set.of(2L));
        snapshot.setDueAmount(null); snapshot.setPenaltyAmount(null);
        snapshot.setProductQualityAmounts(Map.of(2L, new SupplierScoreFactsAggregationService.QualityAmount(BigInteger.valueOf(80), BigInteger.valueOf(20))));
        when(facts.queryFacts(1L, date)).thenReturn(snapshot);
        var result = recalc();
        assertTrue(result.getMetricChanges().stream().noneMatch(change -> change.getMetricType()
                == com.qiheng.erp.purchase.domain.supplierscore.enums.MetricType.DELIVERY));
        assertTrue(result.getMetricChanges().stream().anyMatch(change -> Integer.valueOf(8400).equals(change.getProductRecommendScoreAfter())));
        verifyNoInteractions(aggregation);
    }

    @Test
    void zeroStoredDeliveryIsValidAndMustNotTriggerHistoryQuery() {
        supplier.setDeliveryScore(0);
        FactsSnapshot snapshot = snapshot(BigInteger.valueOf(80), BigInteger.valueOf(20));
        snapshot.setNeedDeliveryRecalculation(false);
        snapshot.setDueAmount(null); snapshot.setPenaltyAmount(null);
        when(facts.queryFacts(1L, date)).thenReturn(snapshot);
        var result = recalc();
        assertTrue(result.getMetricChanges().stream().noneMatch(change -> change.getSupplierProductId() == null
                && change.getMetricType() == com.qiheng.erp.purchase.domain.supplierscore.enums.MetricType.DELIVERY));
        verifyNoInteractions(aggregation);
    }

    @Test
    void nullStoredDeliveryQueriesOnlyDeliveryAndUpdatesAllRecommendations() {
        supplier.setDeliveryScore(null);
        when(products.selectList(any())).thenReturn(List.of(product, otherProduct()));
        FactsSnapshot snapshot = snapshot(BigInteger.valueOf(80), BigInteger.valueOf(20));
        snapshot.setFullQualityRebuild(false);
        snapshot.setAffectedSupplierProductIds(java.util.Set.of());
        snapshot.setNeedDeliveryRecalculation(false);
        snapshot.setDueAmount(null); snapshot.setPenaltyAmount(null);
        when(facts.queryFacts(1L, date)).thenReturn(snapshot);
        when(aggregation.queryDeliveryFacts(1L, date)).thenReturn(
                new SupplierScoreFactsAggregationService.DeliveryFacts(1L, 1000, new BigDecimal("250.125"), 1, 0));
        var result = recalc();
        assertTrue(result.getMetricChanges().stream().anyMatch(change -> change.getSupplierProductId() == null
                && change.getMetricType() == com.qiheng.erp.purchase.domain.supplierscore.enums.MetricType.DELIVERY
                && Integer.valueOf(7499).equals(change.getMetricScoreAfter())));
        verify(products, times(2)).update(isNull(), any(Wrapper.class));
        verify(aggregation).queryDeliveryFacts(1L, date);
        verify(aggregation, never()).queryQualityFacts(anyLong(), any());
        verify(referenceProducts, never()).selectByIds(any());
    }

    @Test
    void nullStoredDeliveryWithoutValidSamplesRemainsNullNotZero() {
        supplier.setDeliveryScore(null);
        FactsSnapshot snapshot = snapshot(BigInteger.valueOf(80), BigInteger.valueOf(20));
        snapshot.setNeedDeliveryRecalculation(false);
        snapshot.setDueAmount(null); snapshot.setPenaltyAmount(null);
        when(facts.queryFacts(1L, date)).thenReturn(snapshot);
        when(aggregation.queryDeliveryFacts(1L, date)).thenReturn(
                new SupplierScoreFactsAggregationService.DeliveryFacts(1L, 0, BigDecimal.ZERO, 0, 0));
        var result = recalc();
        assertTrue(result.getMetricChanges().stream().noneMatch(change -> change.getSupplierProductId() == null
                && change.getMetricType() == com.qiheng.erp.purchase.domain.supplierscore.enums.MetricType.DELIVERY));
        ArgumentCaptor<LambdaUpdateWrapper> capture = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(suppliers).update(isNull(), capture.capture());
        assertTrue(capture.getValue().getParamNameValuePairs().values().contains(null));
        verify(aggregation).queryDeliveryFacts(1L, date);
    }

    @Test
    void partialQualityDoesNotClearOrWriteUnrelatedProduct() {
        SupplierProduct other = otherProduct();
        when(products.selectList(any())).thenReturn(List.of(product, other));
        FactsSnapshot snapshot = snapshot(BigInteger.valueOf(80), BigInteger.valueOf(20));
        snapshot.setFullQualityRebuild(false);
        snapshot.setAffectedSupplierProductIds(java.util.Set.of(2L));
        snapshot.setProductQualityAmounts(Map.of(2L, new SupplierScoreFactsAggregationService.QualityAmount(BigInteger.valueOf(80), BigInteger.valueOf(20))));
        when(facts.queryInboundFacts(1L, date, List.of(99L, 100L))).thenReturn(snapshot);

        var result = service.recalcFactsForSupplier(ScoreRecalcContext.builder().supplierId(1L)
                .completedOrderIds(List.of(99L, 100L)).triggerType(TriggerType.INBOUND_TRIGGER).batchNo("SC-MERGED").build(), date);

        verify(products, times(1)).update(isNull(), any(Wrapper.class));
        assertTrue(result.getMetricChanges().stream().noneMatch(change -> Long.valueOf(4L).equals(change.getSupplierProductId())));
        assertTrue(result.getMetricChanges().stream().noneMatch(change -> change.getMetricType() == com.qiheng.erp.purchase.domain.supplierscore.enums.MetricType.DELIVERY));
        verify(referenceProducts).selectByIds(java.util.Set.of(3L));
        assertEquals(100L, product.getScoreBasisAmount());
        assertEquals(100L, other.getScoreBasisAmount());
    }

    @Test
    void deliveryChangeUpdatesAllRecommendationsButPreservesUnrelatedQualityAndPrice() {
        SupplierProduct other = otherProduct();
        when(products.selectList(any())).thenReturn(List.of(product, other));
        FactsSnapshot snapshot = snapshot(BigInteger.valueOf(90), BigInteger.TEN);
        snapshot.setFullQualityRebuild(false);
        snapshot.setAffectedSupplierProductIds(java.util.Set.of());
        snapshot.setPenaltyAmount(200L);
        when(facts.queryFacts(1L, date)).thenReturn(snapshot);

        var result = recalc();

        verify(products, times(2)).update(isNull(), any(Wrapper.class));
        assertTrue(result.getMetricChanges().stream().filter(change -> change.getSupplierProductId() != null)
                .allMatch(change -> change.getMetricType() == com.qiheng.erp.purchase.domain.supplierscore.enums.MetricType.DELIVERY
                        && change.getMetricScoreBefore() == null && change.getMetricScoreAfter() == null));
        assertTrue(result.getMetricChanges().stream().anyMatch(change -> Long.valueOf(4L).equals(change.getSupplierProductId())
                && Integer.valueOf(7200).equals(change.getProductRecommendScoreAfter())));
        verify(referenceProducts, never()).selectByIds(any());
    }

    @Test
    void queriedProductWithoutSamplesClearsOnlyItsOwnQuality() {
        SupplierProduct other = otherProduct();
        when(products.selectList(any())).thenReturn(List.of(product, other));
        FactsSnapshot snapshot = snapshot(BigInteger.valueOf(80), BigInteger.valueOf(20));
        snapshot.setFullQualityRebuild(false);
        snapshot.setAffectedSupplierProductIds(java.util.Set.of(2L));
        when(facts.queryFacts(1L, date)).thenReturn(snapshot);

        var result = recalc();

        assertTrue(result.getMetricChanges().stream().anyMatch(change -> Long.valueOf(2L).equals(change.getSupplierProductId())
                && change.getMetricType() == com.qiheng.erp.purchase.domain.supplierscore.enums.MetricType.QUALITY
                && change.getMetricScoreAfter() == null));
        verify(products, times(1)).update(isNull(), any(Wrapper.class));
    }

    private SupplierProduct otherProduct() {
        SupplierProduct other = new SupplierProduct();
        other.setId(4L); other.setSupplierId(1L); other.setProductId(5L); other.setVersion(1);
        other.setStatus(1); other.setScoreBasisAmount(100L); other.setQualityScore(7000);
        other.setPriceScore(6000); other.setRecommendScore(7500); other.setScoreStatus("READY");
        return other;
    }

    private FactsSnapshot snapshot(BigInteger q, BigInteger d) {
        FactsSnapshot snapshot = new FactsSnapshot(); snapshot.setQualifiedAmountRaw(q); snapshot.setDefectiveAmountRaw(d);
        snapshot.setDueAmount(1000L); snapshot.setPenaltyAmount(100L); return snapshot;
    }
    private ScoreRecalcResult recalc() {
        return service.recalcFactsForSupplier(ScoreRecalcContext.builder().supplierId(1L)
                .triggerType(TriggerType.DAILY_TRIGGER).batchNo("SC-TEST")
                .operatorType(OperatorType.SYSTEM.name()).build(), date);
    }
}
