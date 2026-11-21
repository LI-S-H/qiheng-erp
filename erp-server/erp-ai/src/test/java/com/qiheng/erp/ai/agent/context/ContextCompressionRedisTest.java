package com.qiheng.erp.ai.agent.context;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.qiheng.erp.ai.config.properties.AiContextProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 压缩写回 Redis 的集成验证：压缩后的列表必须替换检查点里的历史，
 * 并且下一轮能继续执行、最终回复能正常写入。
 *
 * <p>使用真实的 {@link RedisSaver} 与可控模型，覆盖单元测试没有触及的
 * {@code ReplaceAllWith} 持久化和序列化链路。</p>
 *
 * @author Li
 * @since 2026-11-20
 */
@EnabledIfEnvironmentVariable(named = "ERP_SQL_GRAPH_REDIS_TEST", matches = "true")
class ContextCompressionRedisTest {

    private static final String SUMMARY = "## 目标\n分析库存\n## 工作状态\n进行中\n## 下一步\n继续";

    @Test
    void compressedHistoryReplacesCheckpointAndNextTurnContinues() throws Exception {
        Config redisConfig = new Config();
        redisConfig.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(redisConfig);
        try {
            var saver = RedisSaver.builder().redisson(redis)
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            var model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenAnswer(invocation -> Flux.just(
                    new ChatResponse(List.of(new Generation(new AssistantMessage("普通回复"))))));
            when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
                Prompt prompt = invocation.getArgument(0);
                String system = prompt.getSystemMessage() == null ? "" : prompt.getSystemMessage().getText();
                String text = system.contains("结构摘要") ? SUMMARY : "普通回复";
                return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
            });
            // 阈值压到 500、保留 100 token：历史足够触发，压缩后又能落回阈值以下，避免每轮重复压缩。
            var properties = new AiContextProperties(true, 500, 100, 2, false,
                    "flash", 60, 4000, 10_000, 1000, 2000);
            var hook = new ContextCompressionHook(model, properties, "请输出结构摘要", null);
            var agent = ReactAgent.builder().name("compression-redis-test").model(model)
                    .systemPrompt("测试")
                    .hooks(hook)
                    .saver(saver).releaseThread(false).build();
            var config = RunnableConfig.builder()
                    .threadId("compression-redis:" + UUID.randomUUID()).build();

            // 第一轮建立检查点。
            agent.stream("初始问题", config).collectList().block(Duration.ofSeconds(15));
            // 追加一段长历史，模拟会话累积。
            agent.getAndCompileGraph().updateState(config, Map.of("messages", longHistory()));

            // 第二轮触发压缩：检查点中的历史被摘要替换，但最近消息保留。
            agent.stream("继续查安全库存", config).collectList().block(Duration.ofSeconds(15));
            var checkpoint = saver.get(config).orElseThrow().getState();
            var messages = (List<?>) checkpoint.get("messages");
            assertThat(messages).isNotEmpty();
            assertThat(messages.getFirst().toString()).contains(ContextCompressionHook.SUMMARY_PREFIX);
            assertThat(checkpoint.toString()).doesNotContain("初始问题");
            assertThat(checkpoint.toString()).contains("继续查安全库存");

            // 第三轮验证写回后的状态可以继续执行。
            var outputs = agent.stream("再继续", config).collectList().block(Duration.ofSeconds(15));
            assertThat(outputs).isNotEmpty();
            var after = saver.get(config).orElseThrow().getState();
            assertThat(after.toString()).contains("普通回复");
        } finally {
            redis.shutdown();
        }
    }

    private static List<Message> longHistory() {
        return List.of(
                new UserMessage("分析 A 仓库存" + "内容".repeat(100)),
                new AssistantMessage("先查询库存" + "内容".repeat(100)),
                new UserMessage("继续查看明细" + "内容".repeat(100)),
                new AssistantMessage("好的，正在查看" + "内容".repeat(100)));
    }
}