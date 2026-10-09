package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.qiheng.erp.ai.agent.AiAgentConfig;
import org.junit.jupiter.api.Test;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 使用真实 Alibaba Agent 图执行，模拟模型传输，不访问外部模型服务。 */
class AiAgentConfigTest {
    @Test
    void realAgentGraphReturnsOnlyTextDeltasWithoutDuplicatingFinalMessage() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("你好")))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("，ERP"))))));
        var agent = new AiAgentConfig().orchestratorAgent(model);
        List<Message> result = agent.streamMessages(List.of(new UserMessage("你好")),
                        RunnableConfig.builder().threadId("7:100").build())
                .collectList().block(Duration.ofSeconds(5));
        assertEquals("你好，ERP", result.stream().filter(message -> message instanceof AssistantMessage)
                .map(Message::getText).reduce("", String::concat));
    }
}
