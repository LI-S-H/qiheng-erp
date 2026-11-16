package com.qiheng.erp.ai.sql.graph;

import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证不使用持久检查点时图的执行及超时取消行为。 */
class SqlGraphLifecycleProbeTest {
    /** 对比可响应中断和不响应中断的工作，避免将取消请求等同于线程结束。 */
    @Test
    void cancelInterruptsCooperativeWorkButCannotForceUncooperativeWork() throws Exception {
        var entered = new CountDownLatch(1);
        var stopped = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> {
                entered.countDown();
                try {
                    while (release.getCount() > 0) {
                        try {
                            release.await();
                        } catch (InterruptedException ignored) {
                            // 模拟底层操作不响应线程中断，直到测试主动放行。
                        }
                    }
                } finally {
                    stopped.countDown();
                }
            });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> future.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                assertThat(future.cancel(true)).isTrue();
                assertThat(stopped.await(300, TimeUnit.MILLISECONDS)).isFalse();
            } finally {
                release.countDown();
                assertThat(stopped.await(2, TimeUnit.SECONDS)).isTrue();
            }
        }
        var interrupted = new CountDownLatch(1);
        var ready = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> {
                ready.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException error) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(future.cancel(true)).isTrue();
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void graphRunsWithoutPersistentSaver() throws Exception {
        var graph = new StateGraph("sql-lifecycle-probe", () -> Map.of("value", new ReplaceStrategy()))
                .addNode("prepare", node_async(state -> Map.of("value", "prepared")))
                .addNode("finish", node_async(state -> Map.of("value", state.value("value").orElseThrow() + "-done")))
                .addEdge(START, "prepare").addEdge("prepare", "finish").addEdge("finish", END)
                .compile();
        var outputs = graph.stream(Map.of("value", "input")).collectList().block(Duration.ofSeconds(5));
        assertThat(outputs).isNotEmpty();
        assertThat(outputs.getLast().state().value("value")).contains("prepared-done");
    }

    @Test
    void observesWhetherNodeContinuesAfterCallerTimeout() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var graph = new StateGraph("sql-timeout-probe", () -> Map.of("value", new ReplaceStrategy()))
                .addNode("blocked", node_async(state -> {
                    started.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException error) {
                        interrupted.set(true);
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                    return Map.of("value", "done");
                }))
                .addEdge(START, "blocked").addEdge("blocked", END).compile();
        Thread releaser = Thread.ofPlatform().daemon().start(() -> {
            try {
                if (started.await(2, TimeUnit.SECONDS)) {
                    Thread.sleep(1500);
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            } finally {
                release.countDown();
            }
        });
        long startNanos = System.nanoTime();
        var outputs = graph.stream(Map.of("value", "input"))
                .collectList().block(Duration.ofMillis(500));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        releaser.join(3000);
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(outputs).isNotEmpty();
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(1200);
        System.out.println("SQL_TIMEOUT_PROBE timeoutMillis=500 elapsedMillis=" + elapsedMillis
                + " interrupted=" + interrupted.get());
    }
}
