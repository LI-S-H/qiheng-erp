package com.qiheng.erp.ai.sql.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.config.Config;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 用隔离 Redis 验证真实序列化、重建图恢复和同会话状态替换。 */
@EnabledIfEnvironmentVariable(named = "ERP_SQL_GRAPH_REDIS_TEST", matches = "true")
class SqlGraphRedisTest {
    @Test
    void restoresAndReusesSessionWithoutCarryingPreviousWork() throws Exception {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(config);
        try {
            var saver = RedisSaver.builder().redisson(redis)
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            String thread = "sql-test:" + UUID.randomUUID();
            AtomicInteger attempts = new AtomicInteger();
            TestGraph.Operations nodes = new TestGraph.Operations() {
                public Map<String, Object> prepare(Map<String, Object> state) { return state; }
                public Map<String, Object> generate(Map<String, Object> state) {
                    var result = new HashMap<>(state);
                    result.put("sql", "SELECT " + state.get("request"));
                    return result;
                }
                public Map<String, Object> execute(Map<String, Object> state) {
                    if (attempts.getAndIncrement() == 0) throw new IllegalStateException("模拟中断");
                    return state;
                }
                public Map<String, Object> inspect(Map<String, Object> state) {
                    var result = new HashMap<>(state);
                    result.put("datasetId", "ds-" + state.get("request"));
                    result.put("route", "end");
                    return result;
                }
            };
            assertThatThrownBy(() -> TestGraph.create(saver, nodes).start(thread, Map.of("request", "first")))
                    .hasMessageContaining("模拟中断");
            var checkpoint = saver.get(RunnableConfig.builder().threadId(thread).build()).orElseThrow();
            assertThat(checkpoint.getNextNodeId()).isEqualTo("execute");
            SqlGraph graph = TestGraph.create(saver, nodes);
            assertThat(graph.resume(thread, checkpoint.getId()).get("datasetId")).isEqualTo("ds-first");
            graph.clear(thread);
            assertThat(saver.get(RunnableConfig.builder().threadId(thread).build()).orElseThrow()
                    .getState().get("sqlWork")).isEqualTo(Map.of());
            assertThat(graph.start(thread, Map.of("request", "second")).get("datasetId")).isEqualTo("ds-second");
        } finally {
            redis.shutdown();
        }
    }
}
