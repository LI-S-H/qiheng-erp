package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证明确指定工具执行中检查点时，框架会从哪个位置继续。 */
@EnabledIfEnvironmentVariable(named = "ERP_SQL_GRAPH_REDIS_TEST", matches = "true")
class MainAgentRecoveryRedisTest {
    public static class ProbeTool {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        @Tool(name = "probe_recovery", description = "测试工具恢复")
        public String run() throws InterruptedException {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                release.await(30, TimeUnit.SECONDS);
            }
            return "查询完成";
        }
    }

    @Test
    void explicitCheckpointResumeRerunsPendingTool() throws Exception {
        Config redisConfig = new Config();
        redisConfig.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(redisConfig);
        var tool = new ProbeTool();
        try {
            var saver = RedisSaver.builder().redisson(redis)
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            ChatModel model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenAnswer(call -> {
                Prompt prompt = call.getArgument(0);
                boolean done = prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
                var message = done ? new AssistantMessage("完成") : AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall("recovery-call", "function", "probe_recovery", "{}"))).build();
                return Flux.just(new ChatResponse(List.of(new Generation(message))));
            });
            var agent = ReactAgent.builder().name("recovery-probe").model(model)
                    .methodTools(tool).saver(saver).releaseThread(false).build();
            var config = RunnableConfig.builder().threadId("recovery-probe:" + UUID.randomUUID()).build();
            var original = agent.stream("查询", config).subscribeOn(Schedulers.boundedElastic()).subscribe();
            assertThat(tool.entered.await(10, TimeUnit.SECONDS)).isTrue();
            var checkpoint = saver.get(config).orElseThrow();
            System.out.println("待执行节点=" + checkpoint.getNextNodeId());
            original.dispose();
            var resumed = agent.getCompiledGraph().stream(Map.of(), config.withCheckPointId(checkpoint.getId()))
                    .collectList().block(Duration.ofSeconds(15));
            assertThat(resumed).isNotEmpty();
            assertThat(tool.calls.get()).isEqualTo(2);
            assertThat(saver.get(config).orElseThrow().getState().get("messages").toString()).contains("查询完成");
            System.out.println("显式恢复后的工具总调用次数=" + tool.calls.get());
        } finally {
            tool.release.countDown();
            redis.shutdown();
        }
    }
}
