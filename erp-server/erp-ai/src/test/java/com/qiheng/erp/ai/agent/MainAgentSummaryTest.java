package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.qiheng.erp.ai.memory.summary.ContextCompressor;
import com.qiheng.erp.ai.memory.summary.SummaryHook;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证现有摘要 Hook 在真实 ReactAgent 调用模型前生效。 */
class MainAgentSummaryTest {
    @Test
    void usesCompactedHistoryWhenCallingAgentModel() throws Exception {
        ChatModel summary = mock(ChatModel.class);
        when(summary.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("已确定查询一号仓库"))))));
        ChatModel model = mock(ChatModel.class);
        AtomicReference<Prompt> captured = new AtomicReference<>();
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            captured.set(call.getArgument(0));
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("继续分析")))));
        });
        var agent = ReactAgent.builder().name("summary-test").model(model)
                .hooks(new SummaryHook(new ContextCompressor(summary, 10, 1))).build();
        agent.stream(List.of(new UserMessage("库存历史".repeat(100)), new AssistantMessage("旧回复"),
                new UserMessage("继续")), RunnableConfig.builder().threadId("summary-test").build())
                .collectList().block(Duration.ofSeconds(15));
        verify(summary, atLeastOnce()).stream(any(Prompt.class));
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getContents()).contains("已确定查询一号仓库", "继续");
    }
}
