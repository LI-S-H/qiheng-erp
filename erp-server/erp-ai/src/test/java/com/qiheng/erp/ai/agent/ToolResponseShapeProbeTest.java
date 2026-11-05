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

/** 检查真实图执行后进入 beforeModel 的工具结果结构。 */
class ToolResponseShapeProbeTest {
    public static class ProbeTools {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        @Tool(name = "first_probe", description = "第一个测试工具") public String first() { calls.add("first"); return "one"; }
        @Tool(name = "second_probe", description = "第二个测试工具") public String second() { calls.add("second"); return "two"; }
    }
    @HookPositions({HookPosition.BEFORE_MODEL})
    static class Capture extends MessagesModelHook {
        final List<List<Message>> snapshots = new ArrayList<>();
        public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) { snapshots.add(List.copyOf(messages)); return new AgentCommand(null); }
        public String getName() { return "probe_capture"; }
    }
    @Test void oneCall() throws Exception { run(1); }
    @Test void twoCalls() throws Exception { run(2); }
    private void run(int number) throws Exception {
        var model = mock(ChatModel.class); var counter = new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenAnswer(inv -> {
            if (counter.getAndIncrement() > 0) return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("完成")))));
            var calls = new ArrayList<AssistantMessage.ToolCall>();
            calls.add(new AssistantMessage.ToolCall("a", "function", "first_probe", "{}"));
            if (number == 2) calls.add(new AssistantMessage.ToolCall("b", "function", "second_probe", "{}"));
            return Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(calls).build()))));
        });
        var hook = new Capture(); var tools = new ProbeTools();
        var agent = ReactAgent.builder().name("shape_probe").model(model).methodTools(tools).hooks(hook).build();
        agent.stream("开始", RunnableConfig.builder().threadId("shape" + number).build()).collectList().block(Duration.ofSeconds(30));
        assertThat(hook.snapshots).hasSizeGreaterThanOrEqualTo(2);
        var last = hook.snapshots.get(1).getLast();
        assertThat(last).isInstanceOf(ToolResponseMessage.class);
        var response = (ToolResponseMessage) last;
        System.out.println("SHAPE_PROBE calls=" + number + " execution=" + tools.calls + " responses=" + response.getResponses().size() + " ids=" + response.getResponses().stream().map(ToolResponseMessage.ToolResponse::id).toList() + " messageTypes=" + hook.snapshots.get(1).stream().map(m -> m.getClass().getSimpleName()).toList());
        assertThat(response.getResponses()).hasSize(number);
    }
}
