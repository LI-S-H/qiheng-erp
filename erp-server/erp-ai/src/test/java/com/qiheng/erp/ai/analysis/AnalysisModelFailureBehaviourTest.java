package com.qiheng.erp.ai.analysis;

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
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 记录框架在模型调用失败时的行为，作为分析智能体错误处理的依据。
 *
 * <p>框架的模型节点会捕获模型调用异常，并把它转成一条内容为
 * {@code Exception: 原始原因} 的助手消息，图继续跑到结束节点。
 * 因此调用不会抛异常、检查点也已结束，无法靠检查点续跑；
 * 分析智能体必须自己识别这段文本，才能给出准确失败原因。</p>
 */
class AnalysisModelFailureBehaviourTest {

    /** 统计工具执行次数，用于确认失败路径下工具是否被重复执行。 */
    static class ProbeTool {
        static final AtomicInteger EXECUTIONS = new AtomicInteger();

        /** 只用于观察框架行为，不做任何业务处理。 */
        @Tool(name = "probe", description = "观察框架失败行为的探针工具")
        public String probe(@ToolParam(description = "任意内容") String value) {
            EXECUTIONS.incrementAndGet();
            return "ok";
        }
    }

    /**
     * 模型调用失败时，调用不抛异常而是返回错误文本，检查点已到达结束节点。
     */
    @Test
    void modelFailureBecomesErrorTextAndEndsCheckpoint() throws Exception {
        String address = System.getenv().getOrDefault("ERP_AI_RESUME_REDIS", "redis://127.0.0.1:6379");
        Assumptions.assumeTrue(redisReachable(address), "本机没有可用的 Redis，跳过模型失败行为验证");
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
                if (turn == 0) {
                    var call = new AssistantMessage.ToolCall("probe-1", "function", "probe", "{\"value\":\"x\"}");
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                            .content("先跑一个探针工具").toolCalls(List.of(call)).build())));
                }
                throw new IllegalStateException("模拟上游超时");
            });

            var agent = ReactAgent.builder()
                    .name("failure_probe")
                    .model(model)
                    .systemPrompt("你是测试用分析助手")
                    .methodTools(new ProbeTool())
                    .parallelToolExecution(false)
                    .saver(saver)
                    .releaseThread(false)
                    .build();

            var config = RunnableConfig.builder().threadId("analysis:failure-probe").build();
            ProbeTool.EXECUTIONS.set(0);

            // 模型失败不会让调用抛异常。
            var answer = agent.call("分析库存", config);
            assertThat(answer.getText()).startsWith("Exception: ");
            assertThat(answer.getText()).contains("模拟上游超时");

            // 检查点已到结束节点，没有可以续跑的中间状态。
            var checkpoint = saver.get(config).orElseThrow();
            assertThat(checkpoint.getNextNodeId()).isEqualTo("__END__");
        } finally {
            redis.shutdown();
        }
    }

    /**
     * 模型第一次失败、第二次成功时，观察框架是否自动重试，以及对话是否被复制。
     */
    @Test
    void frameworkRetriesModelAfterFailureAndDuplicatesHistory() throws Exception {
        String address = System.getenv().getOrDefault("ERP_AI_RESUME_REDIS", "redis://127.0.0.1:6379");
        Assumptions.assumeTrue(redisReachable(address), "本机没有可用的 Redis，跳过模型重试行为验证");
        Config redissonConfig = new Config();
        redissonConfig.useSingleServer().setAddress(address);
        var redis = Redisson.create(redissonConfig);
        try {
            var saver = RedisSaver.builder().redisson(redis)
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            var model = mock(ChatModel.class);
            var turns = new AtomicInteger();
            var seen = new AtomicReference<String>();
            when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
                int turn = turns.getAndIncrement();
                seen.set(invocation.getArgument(0, Prompt.class).getInstructions().toString());
                if (turn == 0) {
                    var call = new AssistantMessage.ToolCall("probe-1", "function", "probe", "{\"value\":\"x\"}");
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                            .content("先跑一个探针工具").toolCalls(List.of(call)).build())));
                }
                if (turn == 1) {
                    throw new IllegalStateException("模拟上游超时");
                }
                return new ChatResponse(List.of(new Generation(new AssistantMessage("重试后成功"))));
            });

            var agent = ReactAgent.builder()
                    .name("retry_probe")
                    .model(model)
                    .systemPrompt("你是测试用分析助手")
                    .methodTools(new ProbeTool())
                    .parallelToolExecution(false)
                    .saver(saver)
                    .releaseThread(false)
                    .build();

            var config = RunnableConfig.builder().threadId("analysis:retry-probe").build();
            ProbeTool.EXECUTIONS.set(0);

            var answer = agent.call("分析库存", config);
            var checkpoint = saver.get(config).orElseThrow();
            var messages = (List<?>) checkpoint.getState().get("messages");
            System.out.println("=== 第一次失败后自动重试 ===");
            System.out.println("返回: " + answer.getText());
            System.out.println("模型调用次数: " + turns.get());
            System.out.println("工具执行次数: " + ProbeTool.EXECUTIONS.get());
            System.out.println("消息条数: " + messages.size());
            System.out.println("任务描述出现次数: " + countOf(messages.toString(), "分析库存"));
            System.out.println("消息: " + messages);
        } finally {
            redis.shutdown();
        }
    }

    /** 统计片段出现次数，用于确认任务描述是否被重复追加。 */
    private static int countOf(String text, String fragment) {
        int count = 0;
        int index = text.indexOf(fragment);
        while (index >= 0) {
            count++;
            index = text.indexOf(fragment, index + fragment.length());
        }
        return count;
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
