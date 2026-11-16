package com.qiheng.erp.ai.sql.graph;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证同一图对象的两个执行相互隔离。 */
class SqlGraphRedisTest {
    @Test
    void concurrentRunsKeepTheirOwnInputsAndOutputs() throws Exception {
        var entered = new CountDownLatch(2);
        var graph = TestGraph.create(new MemorySaver(), new TestGraph.Operations() {
            public Map<String, Object> prepare(Map<String, Object> state) { return state; }
            public Map<String, Object> generate(Map<String, Object> state) {
                entered.countDown();
                try {
                    if (!entered.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("并发任务未同时进入");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
                return state;
            }
            public Map<String, Object> execute(Map<String, Object> state) { return state; }
            public Map<String, Object> inspect(Map<String, Object> state) {
                var next = new HashMap<>(state);
                next.put("datasetId", "dataset-" + state.get("request"));
                next.put("route", "end");
                return next;
            }
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> graph.start(Map.of("request", "first")));
            var second = executor.submit(() -> graph.start(Map.of("request", "second")));
            assertThat(first.get(10, TimeUnit.SECONDS)).containsEntry("datasetId", "dataset-first");
            assertThat(second.get(10, TimeUnit.SECONDS)).containsEntry("datasetId", "dataset-second");
        }
    }
}
