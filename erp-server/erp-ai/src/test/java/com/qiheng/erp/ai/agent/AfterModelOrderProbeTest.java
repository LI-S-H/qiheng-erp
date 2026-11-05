package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AfterModelOrderProbeTest {
    static List<String> events = Collections.synchronizedList(new ArrayList<>());
    public static class ProbeTool {
        @Tool(name = "probe", description = "测试工具") public String call() { events.add("tool"); return "ok"; }
    }
    @HookPositions({HookPosition.AFTER_MODEL})
    static class ProbeHook extends MessagesModelHook {
        @Override public String getName() { return "order_probe"; }
        @Override public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
            var last = messages.getLast();
            events.add("hook:" + last.getClass().getSimpleName() + ":" + ((AssistantMessage) last).getToolCalls().size());
            return new AgentCommand(null);
        }
    }
    @Test void test() throws Exception {
        events.clear();
        var model = mock(ChatModel.class); var count = new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
            if (count.getAndIncrement() > 0) return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("完成")))));
            var calls = List.of(new AssistantMessage.ToolCall("a", "function", "probe", "{}"));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("开始")))),
                    new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("尾部").toolCalls(calls).build()))));
        });
        var agent = ReactAgent.builder().name("order").model(model).methodTools(new ProbeTool()).hooks(new ProbeHook()).interceptors(new com.qiheng.erp.ai.agent.interceptor.ToolRecordingInterceptor()).build();
        var config = RunnableConfig.builder().threadId("order-test").addMetadata("toolEvents", (java.util.function.Consumer<com.alibaba.cloud.ai.graph.NodeOutput>) output -> events.add("event:" + output.node())).build();
        agent.stream("开始", config)
                .doOnNext(item -> { events.add("out:" + item.node() + ":" + item.getClass().getSimpleName()); if (item.node().contains("order_probe.afterModel")) { var m = (List<?>) item.state().data().get("messages"); events.add("state:" + m.getLast().getClass().getSimpleName() + ":" + ((AssistantMessage) m.getLast()).getText()); } })
                .collectList().block(Duration.ofSeconds(30));
        System.out.println("ORDER_PROBE " + events);
        assertThat(events).contains("state:AssistantMessage:开始尾部");
        assertThat(events.indexOf("out:_AGENT_HOOK_order_probe.afterModel:StreamingOutput"))
                .isGreaterThanOrEqualTo(0).isLessThan(events.indexOf("event:tool_started"));
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "ERP_REAL_QWEN_IT", matches = "true")
    void realModelToolOrder() throws Exception {
        events.clear();
        var yaml = new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.FileSystemResource(java.nio.file.Path.of("..", ".env.ai.yaml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        var model = com.qiheng.erp.ai.config.OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.flash"), 0.2, Duration.ofSeconds(90), false);
        var agent = ReactAgent.builder().name("order_real").model(model).methodTools(new ProbeTool()).hooks(new ProbeHook()).interceptors(new com.qiheng.erp.ai.agent.interceptor.ToolRecordingInterceptor()).build();
        var config = RunnableConfig.builder().threadId(UUID.randomUUID().toString()).addMetadata("toolEvents", (java.util.function.Consumer<com.alibaba.cloud.ai.graph.NodeOutput>) output -> events.add("event:" + output.node())).build();
        agent.stream("请先用一句话说明准备做什么，然后调用 probe 工具，再给出简短回答。", config)
                .doOnNext(item -> events.add("out:" + item.node() + ":" + item.getClass().getSimpleName()))
                .collectList().block(Duration.ofSeconds(120));
        int hook = events.indexOf("out:_AGENT_HOOK_order_probe.afterModel:StreamingOutput");
        org.assertj.core.api.Assertions.assertThat(hook).isGreaterThanOrEqualTo(0).isLessThan(events.indexOf("event:tool_started"));
        System.out.println("REAL_ORDER_PROBE " + events);
    }
}
