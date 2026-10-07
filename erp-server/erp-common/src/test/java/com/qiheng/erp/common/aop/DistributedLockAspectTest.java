package com.qiheng.erp.common.aop;

import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.common.exception.BizException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 分布式锁负租期启用续约；既有固定租期、失败和中断处理保持不变。 */
class DistributedLockAspectTest {
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final ProceedingJoinPoint point = mock(ProceedingJoinPoint.class);
    private final MethodSignature signature = mock(MethodSignature.class);
    private final DistributedLockAspect aspect = new DistributedLockAspect(redisson);

    @BeforeEach
    void setUp() {
        when(point.getSignature()).thenReturn(signature);
        when(point.getArgs()).thenReturn(new Object[0]);
        when(redisson.getLock("score-lock-test")).thenReturn(lock);
    }

    @Test
    void negativeLeaseUsesWatchdogAndReleasesAfterInvocation() throws Throwable {
        var method = Fixture.class.getMethod("watchdog");
        when(signature.getMethod()).thenReturn(method);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(point.proceed()).thenReturn("已提交");
        assertEquals("已提交", aspect.around(point, method.getAnnotation(DistributedLock.class)));
        verify(lock).tryLock(0, TimeUnit.SECONDS);
        verify(lock, never()).tryLock(anyLong(), anyLong(), any(TimeUnit.class));
        var order = inOrder(point, lock);
        order.verify(point).proceed();
        order.verify(lock).unlock();
    }

    @Test
    void defaultLeaseStillUsesThirtySeconds() throws Throwable {
        var method = Fixture.class.getMethod("fixedLease");
        when(signature.getMethod()).thenReturn(method);
        when(lock.tryLock(0, 30, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        aspect.around(point, method.getAnnotation(DistributedLock.class));
        verify(lock).tryLock(0, 30, TimeUnit.SECONDS);
        verify(lock, never()).tryLock(anyLong(), any(TimeUnit.class));
        verify(lock).unlock();
    }

    @Test
    void invocationFailureStillReleasesOwnedLockAndPropagatesException() throws Throwable {
        var method = Fixture.class.getMethod("watchdog");
        when(signature.getMethod()).thenReturn(method);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        var failure = new IllegalStateException("重算回滚");
        when(point.proceed()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> aspect.around(point, method.getAnnotation(DistributedLock.class))));
        verify(lock).unlock();
    }

    @Test
    void busyLockNeverInvokesBusinessOrUnlocksAnotherOwner() throws Throwable {
        var method = Fixture.class.getMethod("watchdog");
        when(signature.getMethod()).thenReturn(method);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);
        assertThrows(BizException.class,
                () -> aspect.around(point, method.getAnnotation(DistributedLock.class)));
        verify(point, never()).proceed();
        verify(lock, never()).unlock();
    }

    @Test
    void interruptedAcquisitionPreservesInterruptAndNeverInvokesBusiness() throws Throwable {
        var method = Fixture.class.getMethod("watchdog");
        when(signature.getMethod()).thenReturn(method);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        try {
            assertThrows(BizException.class,
                    () -> aspect.around(point, method.getAnnotation(DistributedLock.class)));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(point, never()).proceed();
            verify(lock, never()).unlock();
        } finally {
            // 清除本测试设置的中断，避免污染后续测试执行线程。
            Thread.interrupted();
        }
    }

    public static class Fixture {
        @DistributedLock(key = "'score-lock-test'", leaseTime = -1)
        public void watchdog() { }

        @DistributedLock(key = "'score-lock-test'")
        public void fixedLease() { }
    }
}
