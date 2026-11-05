package com.qiheng.erp.ai.sql.graph;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqlGraphTest {
    @Test
    void sameThreadStartsWithFreshWorkAndRetriesOnlyTheFailedQuery() throws Exception {
        MemorySaver saver = new MemorySaver();
        AtomicInteger attempts = new AtomicInteger();
        SqlGraph graph = TestGraph.create(saver, new TestGraph.Operations() {
            public Map<String, Object> prepare(Map<String, Object> state) { return state; }
            public Map<String, Object> generate(Map<String, Object> state) {
                var next = new HashMap<>(state);
                next.put("sql", "SELECT " + state.get("request"));
                next.put("route", "next");
                return next;
            }
            public Map<String, Object> execute(Map<String, Object> state) {
                var next = new HashMap<>(state);
                if (attempts.getAndIncrement() == 0) next.put("route", "retry");
                return next;
            }
            public Map<String, Object> inspect(Map<String, Object> state) {
                var next = new HashMap<>(state);
                next.put("datasetId", "dataset-" + state.get("request"));
                next.put("route", "end");
                return next;
            }
        });
        Map<String, Object> first = graph.start("session-1", Map.of("request", "first"));
        Map<String, Object> second = graph.start("session-1", Map.of("request", "second"));
        assertThat(first.get("datasetId")).isEqualTo("dataset-first");
        assertThat(second.get("datasetId")).isEqualTo("dataset-second");
        assertThat(second.get("sql")).isEqualTo("SELECT second");
        assertThat(attempts).hasValue(3);
        assertThat(saver.list(RunnableConfig.builder().threadId("session-1").build()).size()).isGreaterThan(1);
        graph.clear("session-1");
        var latest = saver.get(RunnableConfig.builder().threadId("session-1").build()).orElseThrow();
        assertThat(latest.getState().get("sqlWork")).isEqualTo(Map.of());
        assertThat(saver.list(RunnableConfig.builder().threadId("session-1").build()).size()).isGreaterThan(1);
    }

    @Test
    void restoresAtCheckpointAfterAnInterruptedNode() throws Exception {
        MemorySaver saver = new MemorySaver();
        AtomicInteger generations = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        TestGraph.Operations operations = new TestGraph.Operations() {
            public Map<String, Object> prepare(Map<String, Object> state) { return state; }
            public Map<String, Object> generate(Map<String, Object> state) {
                generations.incrementAndGet();
                var next = new HashMap<>(state);
                next.put("sql", "SELECT id FROM product");
                return next;
            }
            public Map<String, Object> execute(Map<String, Object> state) {
                if (executions.getAndIncrement() == 0) throw new IllegalStateException("模拟中断");
                return state;
            }
            public Map<String, Object> inspect(Map<String, Object> state) {
                var next = new HashMap<>(state);
                next.put("datasetId", "ds_1");
                next.put("route", "end");
                return next;
            }
        };
        assertThatThrownBy(() -> TestGraph.create(saver, operations).start("session-2", Map.of("request", "库存")))
                .hasMessageContaining("模拟中断");
        var checkpoint = saver.get(RunnableConfig.builder().threadId("session-2").build()).orElseThrow();
        assertThat(checkpoint.getNextNodeId()).isEqualTo("execute");
        var result = TestGraph.create(saver, operations).resume("session-2", checkpoint.getId());
        assertThat(result.get("datasetId")).isEqualTo("ds_1");
        assertThat(generations).hasValue(1);
        assertThat(executions).hasValue(2);
    }
}
