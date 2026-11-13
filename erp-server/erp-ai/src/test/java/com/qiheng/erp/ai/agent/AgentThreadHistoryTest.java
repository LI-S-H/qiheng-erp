package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 核实同一线程多次调用时对话历史是否被重复追加。
 *
 * <p>主智能体的线程号按会话固定，每一轮用户消息都在同一个线程上继续，
 * 如果历史会被复制一遍，上下文会迅速膨胀。</p>
 */
class AgentThreadHistoryTest {

    /**
     * 同一线程连续调用两轮，历史消息条数应线性增长而不是翻倍。
     */
    @Test
    void sameThreadDoesNotDuplicateHistory() throws Exception {
        String address = System.getenv().getOrDefault("ERP_AI_RESUME_REDIS", "redis://127.0.0.1:6379");
        Assumptions.assumeTrue(redisReachable(address), "本机没有可用的 Redis，跳过线程历史验证");
        Config redissonConfig = new Config();
        redissonConfig.useSingleServer().setAddress(address);
        var redis = Redisson.create(redissonConfig);
        try {
            var saver = RedisSaver.builder().redisson(redis)
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            var model = mock(ChatModel.class);
            var turns = new AtomicInteger();
            when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
                int turn = turns.getAndIncrement();
                return new ChatResponse(List.of(new Generation(new AssistantMessage("答复" + turn))));
            });

            var agent = ReactAgent.builder()
                    .name("history_probe")
                    .model(model)
                    .systemPrompt("你是测试用智能体")
                    .parallelToolExecution(false)
                    .saver(saver)
                    .releaseThread(false)
                    .build();

            String threadId = "main:history-probe-" + System.nanoTime();
            var config = RunnableConfig.builder().threadId(threadId).build();

            agent.call("第一轮", config);
            var first = messageCount(saver, config);
            agent.call("第二轮", config);
            var second = messageCount(saver, config);
            agent.call("第三轮", config);
            var third = messageCount(saver, config);

            System.out.println("=== 同一线程连续三轮 ===");
            System.out.println("第一轮后消息条数: " + first);
            System.out.println("第二轮后消息条数: " + second);
            System.out.println("第三轮后消息条数: " + third);
            System.out.println("第一轮消息: " + messages(saver, config));
        } finally {
            redis.shutdown();
        }
    }

    /**
     * 同一线程先成功一轮、再发生模型失败，历史不应被复制。
     */
    @Test
    void modelFailureDoesNotDuplicateExistingHistory() throws Exception {
        String address = System.getenv().getOrDefault("ERP_AI_RESUME_REDIS", "redis://127.0.0.1:6379");
        Assumptions.assumeTrue(redisReachable(address), "本机没有可用的 Redis，跳过线程历史验证");
        Config redissonConfig = new Config();
        redissonConfig.useSingleServer().setAddress(address);
        var redis = Redisson.create(redissonConfig);
        try {
            var saver = RedisSaver.builder().redisson(redis)
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            var model = mock(ChatModel.class);
            var turns = new AtomicInteger();
            when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
                int turn = turns.getAndIncrement();
                if (turn == 1) {
                    throw new IllegalStateException("模拟上游超时");
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("答复" + turn))));
            });

            var agent = ReactAgent.builder()
                    .name("failure_history_probe")
                    .model(model)
                    .systemPrompt("你是测试用智能体")
                    .parallelToolExecution(false)
                    .saver(saver)
                    .releaseThread(false)
                    .build();

            String threadId = "main:failure-history-" + System.nanoTime();
            var config = RunnableConfig.builder().threadId(threadId).build();

            agent.call("第一轮", config);
            var first = messageCount(saver, config);
            agent.call("第二轮", config);
            var second = messageCount(saver, config);

            System.out.println("=== 同一线程：先成功一轮，再模型失败 ===");
            System.out.println("第一轮后消息条数: " + first);
            System.out.println("失败轮后消息条数: " + second);
            System.out.println("消息: " + messages(saver, config));
        } finally {
            redis.shutdown();
        }
    }

    /** 读取当前线程检查点里的消息条数。 */
    private static int messageCount(RedisSaver saver, RunnableConfig config) {
        return ((List<?>) saver.get(config).orElseThrow().getState().get("messages")).size();
    }

    /** 读取当前线程检查点里的消息。 */
    private static Object messages(RedisSaver saver, RunnableConfig config) {
        return saver.get(config).orElseThrow().getState().get("messages");
    }

    /** 判断本机 Redis 是否可用；不可用时跳过验证，不让常规构建失败。 */
    private static boolean redisReachable(String address) {
        String hostPort = address.replace("redis://", "");
        String[] parts = hostPort.split(":");
        try (var socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(parts[0], Integer.parseInt(parts[1])), 800);
            return true;
        } catch (Exception error) {
            return false;
        }
    }
}
