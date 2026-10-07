package com.qiheng.erp.purchase.service.scoring;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupplierQualityAmountCacheServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final HashOperations<String, Object, Object> hash = mock(HashOperations.class);
    private final Map<String, Map<Object, Object>> store = new HashMap<>();
    private final SupplierQualityAmountCacheService service = new SupplierQualityAmountCacheService(redis);
    private final LocalDate date = LocalDate.of(2026, 9, 26);

    @BeforeEach
    void setup() {
        when(redis.<Object, Object>opsForHash()).thenReturn(hash);
        when(hash.entries(anyString())).thenAnswer(call -> new HashMap<>(store.getOrDefault(call.getArgument(0), Map.of())));
        doAnswer(call -> { store.computeIfAbsent(call.getArgument(0), ignored -> new HashMap<>()).putAll(call.getArgument(1)); return null; })
                .when(hash).putAll(anyString(), anyMap());
        when(redis.expire(anyString(), eq(Duration.ofHours(24)))).thenReturn(true);
        when(redis.delete(anyString())).thenAnswer(call -> store.remove(call.getArgument(0)) != null);
        doAnswer(call -> { store.put(call.getArgument(1), store.remove(call.getArgument(0))); return null; })
                .when(redis).rename(anyString(), anyString());
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(call -> {
                    java.util.List<String> keys = call.getArgument(1);
                    Map<Object, Object> values = store.get(keys.getFirst());
                    if (values == null || !call.getArgument(2).equals(values.get("businessDate"))) return 0L;
                    Object field = call.getArgument(8);
                    if (values.containsKey(field)) return 2L;
                    values.put("qualifiedTotal", call.getArgument(6));
                    values.put("defectiveTotal", call.getArgument(7)); values.put(field, "1");
                    return 1L;
                });
    }

    @Test
    void overwriteUsesSupplierTotalsOnlyWithTtlAndAtomicRename() {
        var amount = new SupplierQualityAmountCacheService.Amounts(BigInteger.valueOf(100), BigInteger.TEN);
        service.overwrite(1L, date, amount);
        assertEquals(amount, service.read(1L, date)); assertNull(service.read(1L, date.plusDays(1)));
        assertEquals(4, store.get("supplier:score:quality:1").size());
        verify(redis).expire(anyString(), eq(Duration.ofHours(24))); verify(redis).rename(anyString(), eq("supplier:score:quality:1"));
    }

    @Test
    void failedRefreshInvalidatesOldSameDayTotalsAndRemovesTemporaryHash() {
        service.overwrite(1L, date, new SupplierQualityAmountCacheService.Amounts(BigInteger.TEN, BigInteger.ONE));
        assertNotNull(service.read(1L, date));
        when(redis.expire(anyString(), eq(Duration.ofHours(24)))).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.overwrite(1L, date,
                new SupplierQualityAmountCacheService.Amounts(BigInteger.valueOf(20), BigInteger.TWO)));
        assertNull(service.read(1L, date), "覆盖失败后旧质量基线不能重新命中");
        assertTrue(store.isEmpty(), "失败临时键和正式旧键都应清理");
    }

    @Test
    void repeatedOrderDoesNotAddAgainAndRawAmountHasNoFloatingPointLoss() {
        BigInteger large = new BigInteger("999999999999999999999999");
        service.overwrite(1L, date, new SupplierQualityAmountCacheService.Amounts(large, BigInteger.ONE));
        var delta = new SupplierQualityAmountCacheService.Amounts(BigInteger.TEN, BigInteger.ONE);
        assertEquals(SupplierQualityAmountCacheService.IncrementResult.APPLIED, service.incrementCompletedOrder(1L, 2L, date, delta));
        assertEquals(SupplierQualityAmountCacheService.IncrementResult.ALREADY_APPLIED, service.incrementCompletedOrder(1L, 2L, date, delta));
        assertEquals(large.add(BigInteger.TEN), service.read(1L, date).qualifiedRaw());
        service.overwrite(1L, date, delta);
        assertFalse(store.get("supplier:score:quality:1").containsKey("applied:2"));
    }

    @Test
    void missingCacheMustNotStartIncrementFromZero() {
        assertEquals(SupplierQualityAmountCacheService.IncrementResult.CACHE_MISS,
                service.incrementCompletedOrder(1L, 2L, date, new SupplierQualityAmountCacheService.Amounts(BigInteger.ONE, BigInteger.ONE)));
        verify(hash, never()).putAll(anyString(), anyMap());
    }

    @Test
    void coldRebuildOrderMarkerPreventsRetryAddingAgain() {
        var amount = new SupplierQualityAmountCacheService.Amounts(BigInteger.TEN, BigInteger.ONE);
        service.overwrite(1L, date, amount);
        service.incrementCompletedOrder(1L, 2L, date,
                new SupplierQualityAmountCacheService.Amounts(BigInteger.ZERO, BigInteger.ZERO));
        assertEquals(SupplierQualityAmountCacheService.IncrementResult.ALREADY_APPLIED,
                service.incrementCompletedOrder(1L, 2L, date, amount));
        assertEquals(amount, service.read(1L, date));
    }

    @Test
    void fullBaselineIncludesAllTodayOrdersAndRejectsTheirLateEvents() {
        var amount = new SupplierQualityAmountCacheService.Amounts(BigInteger.TEN, BigInteger.ONE);
        service.overwrite(1L, date, amount, java.util.Set.of(2L, 3L));
        for (Long orderId : java.util.List.of(2L, 3L)) {
            assertEquals(SupplierQualityAmountCacheService.IncrementResult.ALREADY_APPLIED,
                    service.incrementCompletedOrder(1L, orderId, date, amount));
        }
        assertEquals(amount, service.read(1L, date));
    }
}
