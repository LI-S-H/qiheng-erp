package com.qiheng.erp.ai.sql.graph;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SqlGraphServiceTest {
    /** 前一个工作结束后，等待中的任务由线程池自动执行。 */
    @Test
    void queuedTaskRunsAfterWorkerBecomesAvailable() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var graph = mock(SqlGraph.class);
        when(graph.start(anyMap())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                started.countDown();
                release.await();
            }
            return Map.of("status", "FAILED");
        });
        var mapper = mock(AiConversationMapper.class);
        when(mapper.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var service = new SqlGraphService(graph, mapper, mock(DatasetManager.class), id -> {},
                1, 1, Duration.ofSeconds(5));
        var requirement = Map.<String, Object>of("goal", "库存", "dataRequirement", "FULL");
        var pool = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "workers");
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> service.startForUser(1, 2, "first", requirement));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            var second = callers.submit(() -> service.startForUser(1, 2, "second", requirement));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (pool.getQueue().isEmpty() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(pool.getQueue()).hasSize(1);
            release.countDown();
            assertThat(first.get(3, TimeUnit.SECONDS)).containsEntry("status", "FAILED");
            assertThat(second.get(3, TimeUnit.SECONDS)).containsEntry("status", "FAILED");
            assertThat(calls).hasValue(2);
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    /** 排队任务超时后必须释放队列位置，不能在稍后开始执行。 */
    @Test
    void queuedTimeoutRemovesTaskAndQueueFullReturnsBusy() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var graph = mock(SqlGraph.class);
        when(graph.start(anyMap())).thenAnswer(invocation -> {
            started.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // 模拟阻塞请求尚未响应取消。
                }
            }
            return Map.of("status", "FAILED", "sqlRunId", "first");
        });
        var mapper = mock(AiConversationMapper.class);
        when(mapper.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var service = new SqlGraphService(graph, mapper, mock(DatasetManager.class), id -> {},
                1, 1, Duration.ofSeconds(2));
        var requirement = Map.<String, Object>of("goal", "库存", "dataRequirement", "FULL");
        var pool = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "workers");
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> service.startForUser(1, 2, "first", requirement));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            var second = callers.submit(() -> service.startForUser(1, 2, "second", requirement));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (pool.getQueue().isEmpty() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(pool.getQueue()).hasSize(1);
            assertThat(service.startForUser(1, 2, "third", requirement)).containsEntry("code", "SQL_BUSY");
            assertThat(second.get(4, TimeUnit.SECONDS)).containsEntry("code", "SQL_TIMEOUT");
            assertThat(pool.getQueue()).isEmpty();
            release.countDown();
            first.get(4, TimeUnit.SECONDS);
            verify(graph, times(1)).start(anyMap());
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void rejectsInvalidQueueSettings() {
        assertThatThrownBy(() -> new SqlGraphService(mock(SqlGraph.class), mock(AiConversationMapper.class),
                mock(DatasetManager.class), id -> {}, 1, 0, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 关闭时必须唤醒尚未执行的排队请求。 */
    @Test
    void shutdownCancelsQueuedTask() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var graph = mock(SqlGraph.class);
        when(graph.start(anyMap())).thenAnswer(invocation -> {
            started.countDown();
            while (release.getCount() > 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    // 模拟正在运行的请求不能立即停止。
                }
            }
            return Map.of("status", "FAILED");
        });
        var mapper = mock(AiConversationMapper.class);
        when(mapper.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var service = new SqlGraphService(graph, mapper, mock(DatasetManager.class), id -> {},
                1, 1, Duration.ofSeconds(5));
        var requirement = Map.<String, Object>of("goal", "库存", "dataRequirement", "FULL");
        var pool = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "workers");
        try (var callers = Executors.newFixedThreadPool(2)) {
            var first = callers.submit(() -> service.startForUser(1, 2, "first", requirement));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            var second = callers.submit(() -> service.startForUser(1, 2, "second", requirement));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (pool.getQueue().isEmpty() && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(pool.getQueue()).hasSize(1);
            service.shutdown();
            assertThat(second.get(2, TimeUnit.SECONDS)).containsEntry("code", "SQL_CANCELLED");
            release.countDown();
            first.get(3, TimeUnit.SECONDS);
            verify(graph, times(1)).start(anyMap());
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void timeoutReturnsWhileBlockedNodeFinishesAndCleansAfterwards() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var cancellation = new CountDownLatch(1);
        var graph = TestGraph.create(new MemorySaver(), new TestGraph.Operations() {
            public Map<String, Object> prepare(Map<String, Object> state) { return state; }
            public Map<String, Object> generate(Map<String, Object> state) { return state; }
            public Map<String, Object> execute(Map<String, Object> state) {
                started.countDown();
                while (release.getCount() > 0) {
                    try {
                        release.await();
                    } catch (InterruptedException ignored) {
                        // 模拟未及时响应中断的底层请求。
                    }
                }
                return state;
            }
            public Map<String, Object> inspect(Map<String, Object> state) {
                var next = new HashMap<>(state);
                next.put("status", "FAILED");
                next.put("route", "end");
                return next;
            }
        });
        var mapper = mock(AiConversationMapper.class);
        when(mapper.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var datasets = mock(DatasetManager.class);
        var service = new SqlGraphService(graph, mapper, datasets, id -> cancellation.countDown(),
                Duration.ofMillis(500));
        try {
            var result = service.startForUser(1, 2, "run-timeout", Map.of("goal", "库存", "dataRequirement", "FULL"));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(result).containsEntry("code", "SQL_TIMEOUT");
            assertThat(cancellation.await(2, TimeUnit.SECONDS)).isTrue();
            verify(datasets, never()).cleanupStaged("run-timeout", 1L, 2L);
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    @Test
    void interruptedWorkIsNotCleanedWhileNewRunStartsFresh() throws Exception {
        var attempts = new AtomicInteger();
        var graph = TestGraph.create(new MemorySaver(), new TestGraph.Operations() {
            public Map<String, Object> prepare(Map<String, Object> state) { return state; }
            public Map<String, Object> generate(Map<String, Object> state) { return state; }
            public Map<String, Object> execute(Map<String, Object> state) {
                if (attempts.getAndIncrement() == 0) {
                    throw new IllegalStateException("模拟中断");
                }
                return state;
            }
            public Map<String, Object> inspect(Map<String, Object> state) {
                var next = new HashMap<>(state);
                next.put("status", "FAILED");
                next.put("route", "end");
                return next;
            }
        });
        var mapper = mock(AiConversationMapper.class);
        when(mapper.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var datasets = mock(DatasetManager.class);
        var service = new SqlGraphService(graph, mapper, datasets);
        var requirement = Map.<String, Object>of("goal", "库存", "dataRequirement", "FULL");
        assertThatThrownBy(() -> service.startForUser(1, 2, "run1", requirement)).hasMessageContaining("模拟中断");
        verify(datasets, never()).cleanupStaged("run1", 1, 2);
        assertThat(service.startForUser(1, 2, "run2", requirement)).containsEntry("status", "FAILED")
                .containsEntry("sqlRunId", "run2");
        verify(datasets).cleanupStaged("run2", 1, 2);
        assertThatThrownBy(() -> service.startForUser(1, 3, "run3", requirement)).hasMessageContaining("无权");
    }
}
