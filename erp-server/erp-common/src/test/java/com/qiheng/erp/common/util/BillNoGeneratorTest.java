package com.qiheng.erp.common.util;

import com.qiheng.erp.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BillNoGeneratorTest {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private ToLongFunction<String> maxExistingSequenceFinder;

    private BillNoGenerator generator;

    @BeforeEach
    void setUp() throws InterruptedException {
        generator = new BillNoGenerator(stringRedisTemplate, redissonClient);
        lenient().when(redissonClient.getLock(anyString())).thenReturn(lock);
        lenient().when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        lenient().when(lock.isHeldByCurrentThread()).thenReturn(true);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void shouldInitializeDailySequenceFromDatabaseAndUseDailyLock() {
        String day = LocalDate.now().format(DAY_FMT);
        String dayPrefix = "PO" + day;
        String key = "bill:no:PO:" + day;
        when(valueOperations.get(key)).thenReturn(null);
        when(maxExistingSequenceFinder.applyAsLong(dayPrefix)).thenReturn(7L);
        when(valueOperations.setIfAbsent(key, "7")).thenReturn(true);
        when(valueOperations.increment(key)).thenReturn(8L);

        String billNo = generator.nextNo("PO", maxExistingSequenceFinder);

        assertEquals(dayPrefix + "00008", billNo);
        verify(redissonClient).getLock("bill:no:lock:PO:" + day);
        verify(maxExistingSequenceFinder).applyAsLong(dayPrefix);
        verify(valueOperations).setIfAbsent(key, "7");
        verify(lock).unlock();
    }

    @Test
    void shouldUseCachedSequenceWithoutDatabaseLookup() {
        String day = LocalDate.now().format(DAY_FMT);
        String key = "bill:no:SO:" + day;
        when(valueOperations.get(key)).thenReturn("8");
        when(valueOperations.increment(key)).thenReturn(9L);

        String billNo = generator.nextNo("SO", maxExistingSequenceFinder);

        assertEquals("SO" + day + "00009", billNo);
        verifyNoInteractions(maxExistingSequenceFinder);
    }

    @Test
    void shouldRejectInvalidOrExhaustedSequenceBeforeIncrementing() {
        String day = LocalDate.now().format(DAY_FMT);
        when(valueOperations.get("bill:no:PO:" + day)).thenReturn("100000");

        assertThrows(BizException.class, () -> generator.nextNo("PO", maxExistingSequenceFinder));

        verify(valueOperations, never()).increment(anyString());
        verifyNoInteractions(maxExistingSequenceFinder);
    }

    @Test
    void shouldFailClosedWhenRedisOrLockIsUnavailable() {
        String day = LocalDate.now().format(DAY_FMT);
        when(valueOperations.get("bill:no:PO:" + day)).thenThrow(new IllegalStateException("Redis unavailable"));

        assertThrows(BizException.class, () -> generator.nextNo("PO", maxExistingSequenceFinder));

        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    void shouldFailWithoutAccessingRedisWhenDailyLockCannotBeAcquired() throws InterruptedException {
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(false);

        assertThrows(BizException.class, () -> generator.nextNo("PO", maxExistingSequenceFinder));

        verifyNoInteractions(valueOperations, maxExistingSequenceFinder);
        verify(lock, never()).unlock();
    }

    @Test
    void shouldFailClosedWhenUnlockFails() {
        String day = LocalDate.now().format(DAY_FMT);
        when(valueOperations.get("bill:no:PO:" + day)).thenReturn("8");
        when(valueOperations.increment("bill:no:PO:" + day)).thenReturn(9L);
        doThrow(new IllegalStateException("unlock failed")).when(lock).unlock();

        assertThrows(BizException.class, () -> generator.nextNo("PO", maxExistingSequenceFinder));
    }

    @Test
    void shouldIgnoreMalformedAndOversizedBillNumbersWhenFindingMaximum() {
        String day = LocalDate.now().format(DAY_FMT);
        long maximum = generator.findMaxExistingSequence("PO", List.of(
                "PO" + day + "00008",
                "PO" + day + "99999X",
                "PO" + day + "100000",
                "SO" + day + "99999"));

        assertEquals(8L, maximum);
    }
}
