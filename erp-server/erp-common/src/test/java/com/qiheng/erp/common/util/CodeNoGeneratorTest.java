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

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeNoGeneratorTest {

    private static final CodeNoDefinition PRODUCT_CODE = new CodeNoDefinition("product:code", "P", 6);

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ValueOperations<String, String> valueOperations;
    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;
    @Mock
    private LongSupplier maxExistingSequenceSupplier;

    private CodeNoGenerator generator;

    @BeforeEach
    void setUp() throws InterruptedException {
        generator = new CodeNoGenerator(stringRedisTemplate, redissonClient);
        lenient().when(redissonClient.getLock("code:lock:product:code")).thenReturn(lock);
        lenient().when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(true);
        lenient().when(lock.isHeldByCurrentThread()).thenReturn(true);
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void shouldInitializeMissingRedisSequenceFromDatabaseMaximum() throws InterruptedException {
        when(valueOperations.get("product:code")).thenReturn(null);
        when(maxExistingSequenceSupplier.getAsLong()).thenReturn(7L);
        when(valueOperations.setIfAbsent("product:code", "7")).thenReturn(true);
        when(valueOperations.increment("product:code")).thenReturn(8L);

        String code = generator.nextNo(PRODUCT_CODE, maxExistingSequenceSupplier);

        assertEquals("P000008", code);
        verify(valueOperations).setIfAbsent("product:code", "7");
        verify(valueOperations).increment("product:code");
        verify(lock).unlock();
        verify(lock).tryLock(5L, TimeUnit.SECONDS);
    }

    @Test
    void shouldUseExistingRedisSequenceWithoutDatabaseLookup() {
        when(valueOperations.get("product:code")).thenReturn("8");
        when(valueOperations.increment("product:code")).thenReturn(9L);

        String code = generator.nextNo(PRODUCT_CODE, maxExistingSequenceSupplier);

        assertEquals("P000009", code);
        verifyNoInteractions(maxExistingSequenceSupplier);
    }

    @Test
    void shouldUseSequenceWrittenByAnotherProcessWhenInitializationLosesRace() {
        when(valueOperations.get("product:code")).thenReturn(null, "8");
        when(maxExistingSequenceSupplier.getAsLong()).thenReturn(7L);
        when(valueOperations.setIfAbsent("product:code", "7")).thenReturn(false);
        when(valueOperations.increment("product:code")).thenReturn(9L);

        String code = generator.nextNo(PRODUCT_CODE, maxExistingSequenceSupplier);

        assertEquals("P000009", code);
    }

    @Test
    void shouldRejectInvalidExistingRedisSequenceWithoutIncrementing() {
        when(valueOperations.get("product:code")).thenReturn("P000008");

        assertThrows(BizException.class,
                () -> generator.nextNo(PRODUCT_CODE, maxExistingSequenceSupplier));

        verify(valueOperations, never()).increment("product:code");
        verifyNoInteractions(maxExistingSequenceSupplier);
    }

    @Test
    void shouldRejectExhaustedSequenceBeforeIncrementing() {
        when(valueOperations.get("product:code")).thenReturn("999999");

        assertThrows(BizException.class,
                () -> generator.nextNo(PRODUCT_CODE, maxExistingSequenceSupplier));

        verify(valueOperations, never()).increment("product:code");
    }

    @Test
    void shouldFailClosedWhenRedisAccessFails() {
        when(valueOperations.get("product:code")).thenThrow(new IllegalStateException("Redis unavailable"));

        assertThrows(BizException.class,
                () -> generator.nextNo(PRODUCT_CODE, maxExistingSequenceSupplier));

        verify(valueOperations, never()).increment("product:code");
    }

    @Test
    void shouldFailClosedWhenLockClientFails() {
        when(redissonClient.getLock("code:lock:product:code"))
                .thenThrow(new IllegalStateException("Redis unavailable"));

        assertThrows(BizException.class,
                () -> generator.nextNo(PRODUCT_CODE, maxExistingSequenceSupplier));

        verifyNoInteractions(valueOperations, maxExistingSequenceSupplier);
    }

    @Test
    void shouldFailWithoutAccessingRedisWhenLockCannotBeAcquired() throws InterruptedException {
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenReturn(false);

        assertThrows(BizException.class,
                () -> generator.nextNo(PRODUCT_CODE, maxExistingSequenceSupplier));

        verifyNoInteractions(valueOperations, maxExistingSequenceSupplier);
        verify(lock, never()).unlock();
    }

    @Test
    void shouldFailClosedWhenLockAcquisitionIsInterrupted() throws InterruptedException {
        when(lock.tryLock(5L, TimeUnit.SECONDS)).thenThrow(new InterruptedException("interrupted"));

        assertThrows(BizException.class,
                () -> generator.nextNo(PRODUCT_CODE, maxExistingSequenceSupplier));

        assertEquals(true, Thread.interrupted());
        verifyNoInteractions(valueOperations, maxExistingSequenceSupplier);
    }

    @Test
    void shouldFailClosedWhenUnlockFails() {
        when(valueOperations.get("product:code")).thenReturn("8");
        when(valueOperations.increment("product:code")).thenReturn(9L);
        doThrow(new IllegalStateException("unlock failed")).when(lock).unlock();

        assertThrows(BizException.class,
                () -> generator.nextNo(PRODUCT_CODE, maxExistingSequenceSupplier));

        verifyNoInteractions(maxExistingSequenceSupplier);
    }
}
