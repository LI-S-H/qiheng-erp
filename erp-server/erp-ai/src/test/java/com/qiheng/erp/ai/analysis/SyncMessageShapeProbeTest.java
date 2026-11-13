package com.qiheng.erp.ai.analysis;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SyncMessageShapeProbeTest {
    static class Tools {
        @Tool(name = "first_probe", description = "第一个工具")
        public String first() {
            return "one";
        }
        @Tool(name = "second_probe", description = "第二个工具")
        public String second() {
            return "two";
        }
    }

    @HookPositions({HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL})
    static class Capture extends MessagesModelHook {
        final List<List<Message>> before = new ArrayList<>();
        final List<List<Message>> after = new ArrayList<>();
        @Override
        public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
            before.add(List.copyOf(messages));
            return new AgentCommand(null);
        }
        @Override
        public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
            after.add(List.copyOf(messages));
            return new AgentCommand(null);
        }
        @Override
        public String getName() {
            return "sync_probe";
        }
    }

    @Test
    void observesSyncMessageShape() throws Exception {
        var model = mock(ChatModel.class);
        var count = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            if (count.getAndIncrement() > 0) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("完成"))));
            }
            var calls = List.of(new AssistantMessage.ToolCall("a", "function", "first_probe", "{}"),
                    new AssistantMessage.ToolCall("b", "function", "second_probe", "{}"));
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                    .content("先检查数据").toolCalls(calls).build())));
        });
        var capture = new Capture();
        var agent = ReactAgent.builder().name("sync_shape_probe").model(model)
                .parallelToolExecution(false).methodTools(new Tools()).hooks(capture).build();
        assertThat(agent.call("开始").getText()).isEqualTo("完成");
        System.out.println("SYNC_AFTER=" + capture.after);
        System.out.println("SYNC_BEFORE=" + capture.before);
        assertThat(capture.after.getFirst().getLast()).isInstanceOf(AssistantMessage.class);
        var assistant = (AssistantMessage) capture.after.getFirst().getLast();
        assertThat(assistant.getText()).isEqualTo("先检查数据");
        assertThat(assistant.getToolCalls()).hasSize(2);
        assertThat(capture.before.get(1).getLast()).isInstanceOf(ToolResponseMessage.class);
        assertThat(((ToolResponseMessage) capture.before.get(1).getLast()).getResponses()).hasSize(2);
    }
}
