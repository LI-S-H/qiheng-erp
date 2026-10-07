package com.qiheng.erp.purchase.service.scoring;

import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ScoreFactsQueryServiceTest {
    private final SupplierScoreFactsAggregationService reader = mock(SupplierScoreFactsAggregationService.class);
    private final SupplierQualityAmountCacheService cache = mock(SupplierQualityAmountCacheService.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final ScoreFactsQueryService service = new ScoreFactsQueryService(reader, cache, redisson);
    private final LocalDate date = LocalDate.of(2026, 9, 26);

    @org.junit.jupiter.api.BeforeEach
    void setupLock() throws Exception {
        RLock lock = mock(RLock.class); when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true); when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void fullRecalculationReadsBothWindowsAndRefreshesQualityCache() {
        var amount = new SupplierScoreFactsAggregationService.QualityAmount(BigInteger.TEN, BigInteger.ONE);
        when(reader.queryQualityFacts(1L, date)).thenReturn(new SupplierScoreFactsAggregationService.QualityFacts(1L, Map.of(2L, amount), BigInteger.TEN, BigInteger.ONE, 1, 0, java.util.Set.of(3L)));
        when(reader.queryDeliveryFacts(1L, date)).thenReturn(new SupplierScoreFactsAggregationService.DeliveryFacts(1L, 100, 25, 1, 0));
        var result = service.queryFacts(1L, date);
        assertEquals(amount, result.getProductQualityAmounts().get(2L)); assertEquals(100, result.getDueAmount());
        verify(cache).overwrite(eq(1L), eq(date), any(), eq(java.util.Set.of(3L))); verify(cache, never()).read(anyLong(), any());
        assertTrue(result.isFullQualityRebuild());
        assertTrue(result.isNeedDeliveryRecalculation());
    }

    @Test
    void totalsHitAvoidsSql() {
        var totals = new SupplierQualityAmountCacheService.Amounts(BigInteger.TEN, BigInteger.ONE);
        when(cache.read(1L, date)).thenReturn(totals);
        assertEquals(totals, service.queryQualityTotals(1L, date)); verifyNoInteractions(reader);
    }

    @Test
    void incrementColdStartIncludesOrderOnceNotRebuildThenAdd() throws Exception {
        var facts = new SupplierScoreFactsAggregationService.QualityFacts(1L, Map.of(), BigInteger.TEN, BigInteger.ONE, 1, 0);
        when(reader.queryQualityContribution(2L, date)).thenReturn(facts);
        when(reader.queryQualityFacts(1L, date)).thenReturn(facts);
        RLock lock = mock(RLock.class); when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true); when(lock.isHeldByCurrentThread()).thenReturn(true);
        assertEquals(BigInteger.TEN, service.applyCompletedOrder(2L, date).qualifiedRaw());
        verify(lock).unlock();
        verify(cache).incrementCompletedOrder(eq(1L), eq(2L), eq(date),
                eq(new SupplierQualityAmountCacheService.Amounts(BigInteger.ZERO, BigInteger.ZERO)));
    }

    @Test
    void warmMergedOrdersUseOnlyAffectedQualityAndDoNotReadDeliveryWindow() {
        var totals = new SupplierQualityAmountCacheService.Amounts(BigInteger.valueOf(80), BigInteger.valueOf(20));
        when(cache.read(1L, date)).thenReturn(totals);
        var first = new SupplierScoreFactsAggregationService.QualityFacts(1L, Map.of(), BigInteger.TEN, BigInteger.ONE, 1, 0);
        when(reader.queryQualityContributions(eq(1L), anyCollection(), eq(date))).thenReturn(
                new SupplierScoreFactsAggregationService.CompletedOrderFacts(Map.of(3L, first, 4L, first), java.util.Set.of(2L)));
        when(cache.incrementCompletedOrder(eq(1L), anyLong(), eq(date), any()))
                .thenReturn(SupplierQualityAmountCacheService.IncrementResult.APPLIED);
        when(reader.queryQualityFacts(1L, date, java.util.Set.of(2L))).thenReturn(first);

        var result = service.queryInboundFacts(1L, date, java.util.List.of(3L, 4L, 3L));

        assertFalse(result.isFullQualityRebuild());
        assertEquals(java.util.Set.of(2L), result.getAffectedSupplierProductIds());
        assertEquals(totals.qualifiedRaw(), result.getQualifiedAmountRaw());
        assertFalse(result.isNeedDeliveryRecalculation());
        assertNull(result.getDueAmount());
        assertNull(result.getPenaltyAmountRaw());
        verify(reader, never()).queryQualityFacts(1L, date);
        verify(reader, never()).queryDeliveryFacts(anyLong(), any());
        verify(cache, times(2)).incrementCompletedOrder(eq(1L), anyLong(), eq(date), any());
    }

    @Test
    void abnormalWarmOrderRebuildsBothWindowsBeforeAnyIncrement() {
        when(cache.read(1L, date)).thenReturn(new SupplierQualityAmountCacheService.Amounts(BigInteger.TEN, BigInteger.ONE));
        var invalid = new SupplierScoreFactsAggregationService.QualityFacts(1L, Map.of(), BigInteger.ZERO, BigInteger.ZERO, 0, 1);
        when(reader.queryQualityContributions(eq(1L), anyCollection(), eq(date))).thenReturn(
                new SupplierScoreFactsAggregationService.CompletedOrderFacts(Map.of(3L, invalid), java.util.Set.of(2L)));
        when(reader.queryQualityFacts(1L, date)).thenReturn(new SupplierScoreFactsAggregationService.QualityFacts(
                1L, Map.of(), BigInteger.TEN, BigInteger.ONE, 1, 1));
        when(reader.queryDeliveryFacts(1L, date)).thenReturn(new SupplierScoreFactsAggregationService.DeliveryFacts(1L, 100, 25, 1, 1));
        var result = service.queryInboundFacts(1L, date, java.util.List.of(3L));
        assertTrue(result.isFullQualityRebuild());
        assertEquals(1, result.getSkippedQualityOrders());
        assertEquals(1, result.getSkippedDeliveryOrders());
        assertTrue(result.isNeedDeliveryRecalculation());
        verify(reader).queryDeliveryFacts(1L, date);
        verify(cache, never()).incrementCompletedOrder(anyLong(), anyLong(), any(), any());
    }

    @Test
    void coldMergedOrdersRebuildOnceWithoutAddingOrdersAgain() {
        var quality = new SupplierScoreFactsAggregationService.QualityFacts(1L, Map.of(), BigInteger.TEN, BigInteger.ONE, 1, 0, java.util.Set.of(3L, 4L));
        when(reader.queryQualityFacts(1L, date)).thenReturn(quality);
        when(reader.queryDeliveryFacts(1L, date)).thenReturn(new SupplierScoreFactsAggregationService.DeliveryFacts(1L, 100, 25, 1, 0));

        assertTrue(service.queryInboundFacts(1L, date, java.util.List.of(3L, 4L)).isFullQualityRebuild());

        verify(cache).overwrite(eq(1L), eq(date), any(), eq(java.util.Set.of(3L, 4L)));
        verify(cache, never()).incrementCompletedOrder(anyLong(), anyLong(), any(), any());
        verify(reader, never()).queryQualityContributions(anyLong(), anyCollection(), any());
    }

    @Test
    void cacheExpirationDuringIncrementRestartsWholeBatchFromDb() {
        when(cache.read(1L, date)).thenReturn(new SupplierQualityAmountCacheService.Amounts(BigInteger.TEN, BigInteger.ONE));
        var quality = new SupplierScoreFactsAggregationService.QualityFacts(1L, Map.of(), BigInteger.TEN, BigInteger.ONE, 1, 0);
        when(reader.queryQualityContributions(eq(1L), anyCollection(), eq(date))).thenReturn(
                new SupplierScoreFactsAggregationService.CompletedOrderFacts(Map.of(3L, quality), java.util.Set.of(2L)));
        when(cache.incrementCompletedOrder(eq(1L), eq(3L), eq(date), any()))
                .thenReturn(SupplierQualityAmountCacheService.IncrementResult.CACHE_MISS);
        when(reader.queryQualityFacts(1L, date)).thenReturn(quality);
        when(reader.queryDeliveryFacts(1L, date)).thenReturn(new SupplierScoreFactsAggregationService.DeliveryFacts(1L, 100, 25, 1, 0));

        assertTrue(service.queryInboundFacts(1L, date, java.util.List.of(3L)).isFullQualityRebuild());
        verify(reader).queryQualityFacts(1L, date);
        verify(reader, never()).queryQualityFacts(eq(1L), eq(date), anySet());
    }

    @Test
    void oldOrderInMergedWindowFallsBackWithoutIncrement() {
        when(cache.read(1L, date)).thenReturn(new SupplierQualityAmountCacheService.Amounts(BigInteger.TEN, BigInteger.ONE));
        when(reader.queryQualityContributions(eq(1L), anyCollection(), eq(date))).thenThrow(new IllegalArgumentException("订单昨日完成"));
        var quality = new SupplierScoreFactsAggregationService.QualityFacts(1L, Map.of(), BigInteger.TEN, BigInteger.ONE, 1, 0);
        when(reader.queryQualityFacts(1L, date)).thenReturn(quality);
        when(reader.queryDeliveryFacts(1L, date)).thenReturn(new SupplierScoreFactsAggregationService.DeliveryFacts(1L, 100, 25, 1, 0));

        assertTrue(service.queryInboundFacts(1L, date, java.util.List.of(3L)).isFullQualityRebuild());
        verify(cache, never()).incrementCompletedOrder(anyLong(), anyLong(), any(), any());
    }

}
