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
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 核实主智能体在工具节点中断后的恢复行为。
 *
 * <p>要确认两件事：工具内部抛异常时框架怎么处理；恢复时中断的工具是否会被重新执行。
 * 这决定了「恢复工具内部的图」是否有意义。</p>
 */
class MainAgentToolInterruptRecoveryTest {

    /** 统计工具执行次数，用于判断恢复时工具是否被重跑。 */
    static class ProbeTool {
        static final AtomicInteger EXECUTIONS = new AtomicInteger();

        /** 第一次执行抛出 Error 模拟进程级崩溃，之后正常返回。 */
        @Tool(name = "probe", description = "验证工具中断恢复行为的探针工具")
        public String probe(@ToolParam(description = "任意内容") String value) {
            if (EXECUTIONS.incrementAndGet() == 1) {
                throw new Error("模拟工具执行中进程崩溃");
            }
            return "工具第二次执行成功";
        }
    }

    /**
     * 工具执行中崩溃后，主智能体按既有做法补写中断结果再继续，工具不应被重新执行。
     */
    @Test
    void interruptedToolIsNotExecutedAgainAfterRecovery() throws Exception {
        String address = System.getenv().getOrDefault("ERP_AI_RESUME_REDIS", "redis://127.0.0.1:6379");
        Assumptions.assumeTrue(redisReachable(address), "本机没有可用的 Redis，跳过工具中断恢复验证");
        Config redissonConfig = new Config();
        redissonConfig.useSingleServer().setAddress(address);
        var redis = Redisson.create(redissonConfig);
        try {
            var saver = RedisSaver.builder().redisson(redis)
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            var model = mock(ChatModel.class);
            var turns = new AtomicInteger();
            var secondPrompt = new AtomicReference<String>();
            when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
                int turn = turns.getAndIncrement();
                if (turn == 0) {
                    var call = new AssistantMessage.ToolCall("probe-1", "function", "probe", "{\"value\":\"x\"}");
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                            .content("准备调用探针工具").toolCalls(List.of(call)).build())));
                }
                secondPrompt.set(invocation.getArgument(0, Prompt.class).getInstructions().toString());
                return new ChatResponse(List.of(new Generation(new AssistantMessage("主智能体已看到中断结果"))));
            });

            var agent = ReactAgent.builder()
                    .name("interrupt_probe")
                    .model(model)
                    .systemPrompt("你是测试用主智能体")
                    .methodTools(new ProbeTool())
                    .parallelToolExecution(false)
                    .saver(saver)
                    .releaseThread(false)
                    .build();

            var config = RunnableConfig.builder().threadId("main:interrupt-probe").build();
            ProbeTool.EXECUTIONS.set(0);

            // 第一次调用：工具执行中抛出 Error，整轮中断。
            Exception failure = null;
            try {
                agent.call("请分析库存", config);
            } catch (Throwable error) {
                failure = error instanceof Exception exception ? exception : new RuntimeException(error);
            }
            assertThat(failure).isNotNull();

            // 检查点应保留未完成的工具调用。
            var checkpoint = saver.get(config);
            assertThat(checkpoint).isPresent();
            var state = checkpoint.get().getState();
            var messages = (List<?>) state.get("messages");
            assertThat(messages).isNotNull();
            var last = messages.getLast();
            assertThat(last).isInstanceOf(AssistantMessage.class);
            assertThat(((AssistantMessage) last).getToolCalls()).isNotEmpty();

            // 按主智能体的既有做法补写中断结果。
            var responses = ((AssistantMessage) last).getToolCalls().stream()
                    .map(call -> new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                            "{\"status\":\"INTERRUPTED\",\"message\":\"上次工具调用中断\"}"))
                    .toList();
            agent.getAndCompileGraph().updateState(
                    RunnableConfig.builder(config).checkPointId(checkpoint.get().getId()).build(),
                    Map.of("messages", List.of(ToolResponseMessage.builder().responses(responses).build())));

            // 再次调用：应从中断处继续，工具不应被重新执行。
            var resumed = agent.call("请分析库存", config);
            assertThat(resumed.getText()).isEqualTo("主智能体已看到中断结果");
            assertThat(ProbeTool.EXECUTIONS).hasValue(1);
            assertThat(secondPrompt.get()).contains("INTERRUPTED");

            // 主智能体的检查点状态里只有对话，不含工具内部子图的任何状态。
            var afterState = saver.get(config).orElseThrow().getState();
            assertThat(afterState.keySet()).contains("messages").doesNotContain("analysis");
        } finally {
            redis.shutdown();
        }
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
