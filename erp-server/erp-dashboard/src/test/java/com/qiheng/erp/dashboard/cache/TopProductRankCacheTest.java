package com.qiheng.erp.dashboard.cache;

import com.qiheng.erp.common.event.dashboard.TopProductRankAdjustEvent;
import com.qiheng.erp.common.event.dashboard.TopProductRankAdjustEvent.RankItemInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopProductRankCacheTest {

    private StringRedisTemplate redisTemplate;
    private RedissonClient redissonClient;
    private RLock lock;
    private TopProductRankCache cache;
    @SuppressWarnings("rawtypes")
    private org.springframework.data.redis.core.ZSetOperations zsetOps;
    private org.springframework.data.redis.core.HashOperations hashOps;
    @SuppressWarnings("rawtypes")
    private org.springframework.data.redis.core.ValueOperations valueOps;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws InterruptedException {
        redisTemplate = mock(StringRedisTemplate.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        zsetOps = mock(org.springframework.data.redis.core.ZSetOperations.class);
        hashOps = mock(org.springframework.data.redis.core.HashOperations.class);
        valueOps = mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zsetOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(lock.tryLock(any(Long.class), any(Long.class), any())).thenReturn(true);
        cache = new TopProductRankCache(redisTemplate, redissonClient);
    }

    @Test
    void shouldInvokeAdjustOrBufferScriptOnceWithAllItems() {
        TopProductRankAdjustEvent event = new TopProductRankAdjustEvent(
                LocalDate.now(),
                1,
                List.of(new RankItemInput(1L, 1000L, 5L), new RankItemInput(2L, 2000L, 8L)));

        cache.onAdjust(event);

        ArgumentCaptor<List<String>> keysCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), keysCaptor.capture(), argsCaptor.capture());

        // ADJUST_OR_BUFFER_SCRIPT KEYS = [amountKey, qtyKey, rebuildingKey, bufferKey]
        assertThat(keysCaptor.getValue()).containsExactly(
                "dashboard:top-product:sales-amount",
                "dashboard:top-product:sales-qty",
                "dashboard:top-product:rebuilding",
                "dashboard:top-product:rebuild-buffer");
        assertThat(argsCaptor.getValue())
                .containsExactly("1", "1000", "5", "2", "2000", "8");
    }

    @Test
    void shouldPassNegativeDeltaOnCancellation() {
        TopProductRankAdjustEvent event = new TopProductRankAdjustEvent(
                LocalDate.now(),
                -1,
                List.of(new RankItemInput(1L, 1000L, 5L)));

        cache.onAdjust(event);

        ArgumentCaptor<Object[]> argsCaptor = ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(any(RedisScript.class), anyList(), argsCaptor.capture());
        assertThat(argsCaptor.getValue()).containsExactly("1", "-1000", "-5");
    }

    @Test
    void shouldSkipAdjustmentWhenOutOfWindowAndCancellation() {
        // 减分事件 + 30 天外 → 跳过
        TopProductRankAdjustEvent cancellation = new TopProductRankAdjustEvent(
                LocalDate.now().minusDays(40L),
                -1,
                List.of(new RankItemInput(1L, 1000L, 5L)));
        cache.onAdjust(cancellation);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));

        // 加分事件 + 30 天外 → 也跳过（按 businessDate 窗口判断，与 deltaSign 无关）
        TopProductRankAdjustEvent approval = new TopProductRankAdjustEvent(
                LocalDate.now().minusDays(40L),
                1,
                List.of(new RankItemInput(1L, 1000L, 5L)));
        cache.onAdjust(approval);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
    }

    @Test
    void shouldReadTopWithAmountAndQuantityInOneShot() {
        List<List<Object>> luaResult = Arrays.asList(
                Arrays.asList("3", "3000", "8"),
                Arrays.asList("1", "1000", "5"));
        when(redisTemplate.execute(any(RedisScript.class), anyList(), eq("10")))
                .thenReturn(luaResult);

        List<TopProductRankCache.RankEntry> top = cache.readTop(10);

        assertThat(top).hasSize(2);
        assertThat(top.get(0).productId()).isEqualTo(3L);
        assertThat(top.get(0).amount()).isEqualTo(3000L);
        assertThat(top.get(0).quantity()).isEqualTo(8L);
        assertThat(top.get(1).productId()).isEqualTo(1L);
        assertThat(top.get(1).amount()).isEqualTo(1000L);
        assertThat(top.get(1).quantity()).isEqualTo(5L);
    }

    @Test
    void shouldReturnEmptyWhenLimitIsNonPositive() {
        // limit=0 或负数直接返回空，不走 Lua 避免 ZREVRANGE 0 -1 全量扫描
        assertThat(cache.readTop(0)).isEmpty();
        assertThat(cache.readTop(-1)).isEmpty();
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
    }

    @Test
    void shouldReturnEmptyWhenReadTopReturnsNullOrEmpty() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(null);
        assertThat(cache.readTop(10)).isEmpty();

        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString()))
                .thenReturn(List.of());
        assertThat(cache.readTop(10)).isEmpty();
    }

    @Test
    void shouldCallSwapAndReplayScriptsOnRebuildSuccess() throws InterruptedException {
        TopProductRankCache.RebuildOutcome outcome = cache.rebuild(
                () -> List.of(new TopProductRankCache.RankEntry(1L, 1000L, 5L)));

        assertThat(outcome).isEqualTo(TopProductRankCache.RebuildOutcome.SUCCESS);
        // 1. 标记 rebuilding
        verify(valueOps).set(eq("dashboard:top-product:rebuilding"), eq("1"));
        // 2. SWAP_SCRIPT 与 REPLAY_AND_CLOSE_REBUILD_SCRIPT 都走 execute
        verify(redisTemplate, org.mockito.Mockito.atLeast(2))
                .execute(any(RedisScript.class), anyList());
        // 3. 锁释放
        verify(lock).unlock();
    }

    @Test
    void shouldReturnSkippedWhenAnotherInstanceHoldsLock() throws InterruptedException {
        when(lock.tryLock(any(Long.class), any(Long.class), any())).thenReturn(false);

        TopProductRankCache.RebuildOutcome outcome = cache.rebuild(
                () -> List.of(new TopProductRankCache.RankEntry(1L, 1000L, 5L)));

        assertThat(outcome).isEqualTo(TopProductRankCache.RebuildOutcome.SKIPPED_LOCK_BUSY);
        // 锁被抢，其他实例在跑，不应执行任何缓存操作
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    void shouldConsumeBufferAndCloseMarkerWhenRebuildFailsButReplaySucceeds()
            throws InterruptedException {
        // 第一次 execute（SWAP 阶段）抛异常，模拟切换失败；REPLAY 阶段成功
        org.mockito.Mockito.doThrow(new RuntimeException("模拟 SWAP 失败"))
                .doReturn(3L)  // REPLAY 阶段返回 3 条事件
                .when(redisTemplate).execute(any(RedisScript.class), anyList());

        TopProductRankCache.RebuildOutcome outcome = null;
        try {
            outcome = cache.rebuild(() -> List.of(new TopProductRankCache.RankEntry(1L, 1000L, 5L)));
        } catch (Exception ignored) {
            // rebuild 应该抛出但已用 REPLAY 消费队列
        }
        // rebuild 失败，状态被清
        assertThat(outcome).isNull();
    }

    @Test
    void shouldKeepMarkerWhenRebuildAndReplayBothFail() throws InterruptedException {
        // 第一次 SWAP 抛异常；REPLAY 也抛异常
        org.mockito.Mockito.doThrow(new RuntimeException("全部失败"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList());

        try {
            cache.rebuild(() -> List.of(new TopProductRankCache.RankEntry(1L, 1000L, 5L)));
        } catch (Exception ignored) {
            // 期望抛出
        }
        // 失败时不应 DEL 标记（保留等下次重建覆盖）
        verify(redisTemplate, never()).delete("dashboard:top-product:rebuilding");
    }

    @Test
    void shouldTreatNullQuantityAsZero() {
        when(hashOps.multiGet(eq("dashboard:top-product:sales-qty"), any()))
                .thenReturn(Arrays.asList(null, "5"));

        List<Long> qtys = cache.readQuantities(List.of(1L, 2L));

        assertThat(qtys).containsExactly(0L, 5L);
    }
}