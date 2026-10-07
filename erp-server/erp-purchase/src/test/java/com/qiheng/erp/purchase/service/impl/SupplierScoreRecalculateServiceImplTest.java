package com.qiheng.erp.purchase.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeBatchCommand;
import com.qiheng.erp.purchase.domain.supplierscore.enums.MetricType;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.purchase.mapper.SupplierScoreChangeLogMapper;
import com.qiheng.erp.purchase.service.ISupplierScoreChangeLogService;
import com.qiheng.erp.purchase.service.scoring.AggregateScoreCalculator;
import com.qiheng.erp.purchase.service.scoring.DeliveryScoreCalculator;
import com.qiheng.erp.purchase.service.scoring.PriceScoreCalculator;
import com.qiheng.erp.purchase.service.scoring.QualityScoreCalculator;
import com.qiheng.erp.purchase.service.scoring.ScoreFactsQueryService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.times;
import org.mockito.ArgumentCaptor;

/** 验证报价同步路径确实计算并记录产品与供应商价格变化。 */
class SupplierScoreRecalculateServiceImplTest {
    private org.mockito.MockedStatic<com.qiheng.erp.security.context.UserContext> userContext;
    private final SupplierMapper supplierMapper = mock(SupplierMapper.class);
    private final SupplierProductMapper supplierProductMapper = mock(SupplierProductMapper.class);
    private final ProductMapper productMapper = mock(ProductMapper.class);
    private final ISupplierScoreChangeLogService changeLogService = mock(ISupplierScoreChangeLogService.class);
    private final BillNoGenerator billNoGenerator = mock(BillNoGenerator.class);
    private final SupplierScoreChangeLogMapper changeLogMapper = mock(SupplierScoreChangeLogMapper.class);
    private final ScoreFactsQueryService factsQueryService = mock(ScoreFactsQueryService.class);

    @BeforeEach
    void initMetadata() {
        // 人工入口必须记录真实登录身份，不能以 SYSTEM 默认值掩盖缺失。
        userContext = org.mockito.Mockito.mockStatic(com.qiheng.erp.security.context.UserContext.class);
        var user = new com.qiheng.erp.security.domain.dto.LoginUser();
        user.setUserId(700L); user.setUsername("score-review"); user.setRealName("评分审阅员");
        userContext.when(com.qiheng.erp.security.context.UserContext::requireCurrentUser).thenReturn(user);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), Supplier.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), SupplierProduct.class);
        when(supplierMapper.lockByIdForUpdate(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @AfterEach
    void closeUserContext() { userContext.close(); }

    @Test
    @SuppressWarnings("unchecked")
    void quoteChangeRecalculatesProductAndWeightedSupplierPrice() {
        Supplier supplier = new Supplier().setId(1L).setPriceScore(8000).setDeliveryScore(9000)
                .setQualityScore(9000).setServiceScore(9000).setOverallScore(8700)
                .setScoreStatus("READY");
        SupplierProduct sp = new SupplierProduct().setId(10L).setSupplierId(1L).setProductId(20L)
                .setStatus(1).setQuotedPurchasePrice(1000L)
                .setQuoteValidUntil(LocalDate.now().plusDays(1)).setScoreBasisAmount(100L)
                .setPriceScore(8000).setQualityScore(9000).setRecommendScore(8700)
                .setScoreStatus("READY");
        SupplierProduct unrelated = new SupplierProduct().setId(11L).setSupplierId(1L).setProductId(21L)
                .setStatus(1).setPriceScore(8000).setQualityScore(9000)
                .setRecommendScore(8700).setScoreStatus("READY");
        Product product = new Product().setId(20L).setReferencePurchasePrice(new BigDecimal("20.00"));
        when(supplierProductMapper.selectById(10L)).thenReturn(sp);
        when(supplierMapper.selectById(1L)).thenReturn(supplier);
        when(supplierProductMapper.selectList(any())).thenReturn(List.of(sp, unrelated));
        when(productMapper.selectByIds(any())).thenReturn(List.of(product));
        when(supplierMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(supplierProductMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(billNoGenerator.nextNo(eq("SC"), any(ToLongFunction.class))).thenReturn("SC2026093000001");

        var service = newService();
        service.recalcForQuoteChange(10L, "报价测试原因");

        ArgumentCaptor<ScoreChangeBatchCommand> command = ArgumentCaptor.forClass(ScoreChangeBatchCommand.class);
        verify(changeLogService).appendBatch(command.capture());
        assertTrue(command.getValue().getBatchNo().matches("SC\\d{13}"));
        // 单个报价变更不应更新同供应商的其他供货产品。
        verify(supplierProductMapper).update(eq(null), any(Wrapper.class));
        assertEquals("SC2026093000001", command.getValue().getBatchNo());
        assertEquals("USER", command.getValue().getOperatorType());
        assertEquals(700L, command.getValue().getOperatorId());
        assertEquals("评分审阅员", command.getValue().getOperatorName());
        assertEquals("报价测试原因", command.getValue().getReason());
        assertEquals("10", command.getValue().getRelatedSources().getFirst().getBusinessId());
        assertEquals(com.qiheng.erp.purchase.domain.supplierscore.enums.ScoreSourceBusinessType.SUPPLIER_PRODUCT,
                command.getValue().getRelatedSources().getFirst().getBusinessType());
        assertTrue(command.getValue().getEntries().stream().anyMatch(entry ->
                entry.getMetricType() == MetricType.PRICE && Long.valueOf(10L).equals(entry.getSupplierProductId())
                        && Integer.valueOf(10000).equals(entry.getMetricScoreAfter())));
        assertTrue(command.getValue().getEntries().stream().anyMatch(entry ->
                entry.getMetricType() == MetricType.PRICE && entry.getSupplierProductId() == null
                        && Integer.valueOf(10000).equals(entry.getMetricScoreAfter())));
    }

    @Test
    void serviceScoreChangeOnlyRecalculatesOverallAndRecommendations() {
        Supplier supplier = new Supplier().setId(1L).setServiceScore(9000).setPriceScore(8000)
                .setDeliveryScore(8000).setQualityScore(8000).setOverallScore(8000).setScoreStatus("READY");
        SupplierProduct sp = new SupplierProduct().setId(10L).setSupplierId(1L)
                .setPriceScore(8000).setQualityScore(8000).setRecommendScore(8000).setScoreStatus("READY");
        when(supplierMapper.selectById(1L)).thenReturn(supplier);
        when(supplierProductMapper.selectList(any())).thenReturn(List.of(sp));
        when(supplierMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(supplierProductMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(billNoGenerator.nextNo(eq("SC"), any(ToLongFunction.class))).thenReturn("SC2026093000002");

        newService().recalcForServiceScoreChange(1L, 8000, "服务改善");

        verifyNoInteractions(factsQueryService, productMapper);
        ArgumentCaptor<ScoreChangeBatchCommand> command = ArgumentCaptor.forClass(ScoreChangeBatchCommand.class);
        verify(changeLogService).appendBatch(command.capture());
        assertEquals("SC2026093000002", command.getValue().getBatchNo());
        assertEquals(700L, command.getValue().getOperatorId());
        assertEquals("服务改善", command.getValue().getReason());
        assertEquals("1", command.getValue().getRelatedSources().getFirst().getBusinessId());
        assertTrue(command.getValue().getEntries().stream().anyMatch(entry ->
                entry.getMetricType() == MetricType.SERVICE && entry.getSupplierProductId() == null
                        && Integer.valueOf(8000).equals(entry.getMetricScoreBefore())
                        && Integer.valueOf(9000).equals(entry.getMetricScoreAfter())));
        assertTrue(command.getValue().getEntries().stream().anyMatch(entry ->
                Long.valueOf(10L).equals(entry.getSupplierProductId())
                        && Integer.valueOf(8100).equals(entry.getProductRecommendScoreAfter())));
    }

    @Test
    void expiredQuoteClearsProductAndSupplierPriceWithoutReadingHistory() {
        Supplier supplier = new Supplier().setId(1L).setServiceScore(9000).setPriceScore(8000)
                .setDeliveryScore(9000).setQualityScore(9000).setOverallScore(8700).setScoreStatus("READY");
        SupplierProduct sp = new SupplierProduct().setId(10L).setSupplierId(1L).setProductId(20L)
                .setStatus(1).setQuotedPurchasePrice(1000L).setQuoteValidUntil(LocalDate.now().minusDays(1))
                .setScoreBasisAmount(100L).setPriceScore(8000).setQualityScore(9000)
                .setRecommendScore(8700).setScoreStatus("READY");
        when(supplierMapper.selectById(1L)).thenReturn(supplier);
        when(supplierProductMapper.selectList(any())).thenReturn(List.of(sp));
        when(productMapper.selectByIds(any())).thenReturn(List.of(
                new Product().setId(20L).setReferencePurchasePrice(new BigDecimal("20.00"))));
        when(supplierMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(supplierProductMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(billNoGenerator.nextNo(eq("SC"), any(ToLongFunction.class))).thenReturn("SC2026093000003");

        newService().recalcPricesForSupplier(1L);

        var order = org.mockito.Mockito.inOrder(supplierMapper, supplierProductMapper);
        order.verify(supplierMapper).lockByIdForUpdate(1L);
        order.verify(supplierMapper).selectById(1L);
        order.verify(supplierProductMapper).selectList(any());

        verifyNoInteractions(factsQueryService);
        ArgumentCaptor<ScoreChangeBatchCommand> command = ArgumentCaptor.forClass(ScoreChangeBatchCommand.class);
        verify(changeLogService).appendBatch(command.capture());
        assertEquals("SC2026093000003", command.getValue().getBatchNo());
        assertEquals("SYSTEM", command.getValue().getOperatorType());
        assertEquals("10", command.getValue().getRelatedSources().getFirst().getBusinessId());
        assertTrue(command.getValue().getEntries().stream().anyMatch(entry ->
                entry.getMetricType() == MetricType.PRICE && Long.valueOf(10L).equals(entry.getSupplierProductId())
                        && entry.getMetricScoreAfter() == null));
        assertTrue(command.getValue().getEntries().stream().anyMatch(entry ->
                entry.getMetricType() == MetricType.PRICE && entry.getSupplierProductId() == null
                        && entry.getMetricScoreAfter() == null));
    }

    @Test
    void referencePriceChangeRecalculatesEveryAffectedSupplier() {
        when(productMapper.selectById(20L)).thenReturn(new Product().setId(20L).setProductCode("P000020"));
        Supplier firstSupplier = new Supplier().setId(1L).setServiceScore(9000).setPriceScore(8000)
                .setDeliveryScore(9000).setQualityScore(9000).setOverallScore(8700).setScoreStatus("READY");
        Supplier secondSupplier = new Supplier().setId(2L).setServiceScore(9000).setPriceScore(8000)
                .setDeliveryScore(9000).setQualityScore(9000).setOverallScore(8700).setScoreStatus("READY");
        SupplierProduct first = new SupplierProduct().setId(10L).setSupplierId(1L).setProductId(20L)
                .setStatus(1).setQuotedPurchasePrice(1000L).setQuoteValidUntil(LocalDate.now().plusDays(1))
                .setScoreBasisAmount(100L).setPriceScore(8000).setQualityScore(9000)
                .setRecommendScore(8700).setScoreStatus("READY");
        SupplierProduct second = new SupplierProduct().setId(11L).setSupplierId(2L).setProductId(20L)
                .setStatus(1).setQuotedPurchasePrice(1000L).setQuoteValidUntil(LocalDate.now().plusDays(1))
                .setScoreBasisAmount(100L).setPriceScore(8000).setQualityScore(9000)
                .setRecommendScore(8700).setScoreStatus("READY");
        when(supplierProductMapper.selectList(any())).thenReturn(List.of(second, first), List.of(first), List.of(second));
        when(supplierMapper.selectById(1L)).thenReturn(firstSupplier);
        when(supplierMapper.selectById(2L)).thenReturn(secondSupplier);
        when(productMapper.selectByIds(any())).thenReturn(List.of(
                new Product().setId(20L).setReferencePurchasePrice(new BigDecimal("20.00"))));
        when(supplierMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(supplierProductMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(billNoGenerator.nextNo(eq("SC"), any(ToLongFunction.class)))
                .thenReturn("SC2026093000004", "SC2026093000005");

        newService().recalcForProductReferencePriceChange(20L);

        var order = org.mockito.Mockito.inOrder(supplierMapper);
        order.verify(supplierMapper).lockByIdForUpdate(1L);
        order.verify(supplierMapper).lockByIdForUpdate(2L);
        order.verify(supplierMapper).selectById(1L);

        ArgumentCaptor<ScoreChangeBatchCommand> commands = ArgumentCaptor.forClass(ScoreChangeBatchCommand.class);
        verify(changeLogService, times(2)).appendBatch(commands.capture());
        assertEquals(List.of(1L, 2L), commands.getAllValues().stream()
                .map(ScoreChangeBatchCommand::getSupplierId).toList());
        assertEquals(List.of("SC2026093000004", "SC2026093000005"), commands.getAllValues().stream()
                .map(ScoreChangeBatchCommand::getBatchNo).toList());
        for (var command : commands.getAllValues()) {
            assertEquals(700L, command.getOperatorId());
            assertEquals("20", command.getRelatedSources().getFirst().getBusinessId());
            assertEquals(com.qiheng.erp.purchase.domain.supplierscore.enums.ScoreSourceBusinessType.PRODUCT,
                    command.getRelatedSources().getFirst().getBusinessType());
        }
        verifyNoInteractions(factsQueryService);
    }

    @Test
    void oneExpiredQuoteOnlyRemovesItsWeightAndPreservesOtherRecommendation() {
        Supplier supplier = new Supplier().setId(1L).setServiceScore(9000).setPriceScore(6750)
                .setDeliveryScore(9000).setQualityScore(9000).setOverallScore(8325).setScoreStatus("READY");
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        SupplierProduct expired = new SupplierProduct().setId(10L).setSupplierId(1L).setProductId(20L)
                .setStatus(1).setQuotedPurchasePrice(10000L).setQuoteValidUntil(today.minusDays(1))
                .setScoreBasisAmount(1000L).setPriceScore(9000).setQualityScore(9000)
                .setRecommendScore(9000).setScoreStatus("READY");
        SupplierProduct valid = new SupplierProduct().setId(11L).setSupplierId(1L).setProductId(21L)
                .setStatus(1).setQuotedPurchasePrice(10000L).setQuoteValidUntil(today)
                .setScoreBasisAmount(3000L).setPriceScore(6000).setQualityScore(9000)
                .setRecommendScore(8100).setScoreStatus("READY");
        when(supplierMapper.selectById(1L)).thenReturn(supplier);
        when(supplierProductMapper.selectList(any())).thenReturn(List.of(expired, valid));
        when(productMapper.selectByIds(any())).thenReturn(List.of(
                new Product().setId(20L).setReferencePurchasePrice(new BigDecimal("90.00")),
                new Product().setId(21L).setReferencePurchasePrice(new BigDecimal("60.00"))));
        when(supplierMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(supplierProductMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(billNoGenerator.nextNo(eq("SC"), any(ToLongFunction.class))).thenReturn("SC2026093000006");

        newService().recalcPricesForSupplier(1L);

        ArgumentCaptor<ScoreChangeBatchCommand> command = ArgumentCaptor.forClass(ScoreChangeBatchCommand.class);
        verify(changeLogService).appendBatch(command.capture());
        assertTrue(command.getValue().getEntries().stream().anyMatch(entry ->
                entry.getSupplierProductId() == null && Integer.valueOf(6000).equals(entry.getMetricScoreAfter())
                        && Integer.valueOf(8100).equals(entry.getSupplierOverallScoreAfter())));
        verify(supplierProductMapper, times(1)).update(eq(null), any(Wrapper.class));
        assertEquals(List.of("10"), command.getValue().getRelatedSources().stream()
                .map(com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeSource::getBusinessId).toList(),
                "到期来源不得混入仍有效的供货关系");
        verifyNoInteractions(factsQueryService);
    }

    @Test
    void missingSupplierStopsExpiryBeforeReadingProductsOrHistory() {
        when(supplierMapper.lockByIdForUpdate(1L)).thenReturn(null);
        org.junit.jupiter.api.Assertions.assertThrows(com.qiheng.erp.common.exception.BizException.class,
                () -> newService().recalcPricesForSupplier(1L));
        verifyNoInteractions(supplierProductMapper, productMapper, factsQueryService, changeLogService);
    }

    @Test
    void clearingQuotePreservesSubmittedReasonAndUserInsteadOfReadingClearedQuoteFields() {
        var supplier = new Supplier().setId(1L).setPriceScore(8000).setQualityScore(9000)
                .setDeliveryScore(9000).setServiceScore(9000).setOverallScore(8700).setScoreStatus("READY");
        var sp = new SupplierProduct().setId(10L).setSupplierId(1L).setProductId(20L).setStatus(1)
                .setQuotedPurchasePrice(null).setPriceScore(8000).setQualityScore(9000)
                .setScoreBasisAmount(100L).setRecommendScore(8700).setScoreStatus("READY");
        when(supplierProductMapper.selectById(10L)).thenReturn(sp);
        when(supplierMapper.selectById(1L)).thenReturn(supplier);
        when(supplierProductMapper.selectList(any())).thenReturn(List.of(sp));
        when(productMapper.selectByIds(any())).thenReturn(List.of(new Product().setId(20L)
                .setReferencePurchasePrice(new BigDecimal("20.00"))));
        when(supplierMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(supplierProductMapper.update(eq(null), any(Wrapper.class))).thenReturn(1);
        when(billNoGenerator.nextNo(eq("SC"), any(ToLongFunction.class))).thenReturn("SC2026093000007");
        newService().recalcForQuoteChange(10L, "供应商撤回报价");
        var command = ArgumentCaptor.forClass(ScoreChangeBatchCommand.class);
        verify(changeLogService).appendBatch(command.capture());
        assertEquals("供应商撤回报价", command.getValue().getReason());
        assertEquals(700L, command.getValue().getOperatorId());
        assertEquals("10", command.getValue().getRelatedSources().getFirst().getBusinessId());
        assertTrue(command.getValue().getEntries().stream().anyMatch(entry -> entry.getMetricType() == MetricType.PRICE
                && Long.valueOf(10L).equals(entry.getSupplierProductId()) && entry.getMetricScoreAfter() == null));
        verifyNoInteractions(factsQueryService);
    }

    private SupplierScoreRecalculateServiceImpl newService() {
        return new SupplierScoreRecalculateServiceImpl(new QualityScoreCalculator(),
                new DeliveryScoreCalculator(), new PriceScoreCalculator(), new AggregateScoreCalculator(),
                factsQueryService, org.mockito.Mockito.mock(com.qiheng.erp.purchase.service.scoring.SupplierScoreFactsAggregationService.class),
                changeLogService, supplierMapper, supplierProductMapper,
                productMapper, billNoGenerator, changeLogMapper);
    }
}
