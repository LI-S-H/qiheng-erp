package com.qiheng.erp.ai.memory.summary;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ContextCompressorTest {

    @Test
    void summaryFailureBlankAndTruncationPreserveOriginalMessages() {
        ChatModel model = mock(ChatModel.class);
        ContextCompressor compressor = new ContextCompressor(model, 10, 1);
        List<Message> original = List.of(new UserMessage("历史".repeat(100)), new AssistantMessage("最近"));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("摘要失败")));
        assertSame(original, compressor.compact(original));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response(" ")));
        assertSame(original, compressor.compact(original));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(
                new Generation(new AssistantMessage("被截断"),
                        ChatGenerationMetadata.builder().finishReason("length").build())))));
        assertSame(original, compressor.compact(original));
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("有效摘要")));
        assertNotSame(original, compressor.compact(original));
    }

    @Test
    void projectsToolEvidenceAndRestoresRecentOriginalObjectsWithoutRegistry() {
        AtomicReference<Prompt> captured = new AtomicReference<>();
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return Flux.just(response("仓库A库存为120"));
        });
        AssistantMessage call = AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "queryStock",
                        "{\"warehouse\":\"A\"}"))).build();
        ToolResponseMessage result = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "queryStock",
                        "{\"stockQty\":120}"))).build();
        UserMessage recentUser = new UserMessage("然后呢");
        AssistantMessage recentAssistant = new AssistantMessage("待确认");
        List<Message> original = List.of(new UserMessage("需求".repeat(100)), call, result,
                recentUser, recentAssistant);
        ContextCompressor compressor = new ContextCompressor(model, 10, 2);
        // 不传线程ID、租约、服务或registry也能独立调用。
        List<Message> compacted = compressor.compact(original);
        assertEquals(3, compacted.size());
        assertInstanceOf(SystemMessage.class, compacted.getFirst());
        assertSame(recentUser, compacted.get(1));
        assertSame(recentAssistant, compacted.get(2));
        assertTrue(captured.get().getContents().contains("warehouse"));
        assertTrue(captured.get().getContents().contains("stockQty"));
        assertEquals("", call.getText());
        assertEquals("", result.getText());
    }

    @Test
    void lowVolumeDoesNotCallModelAndHookDelegatesReplacement() {
        ChatModel model = mock(ChatModel.class);
        ContextCompressor compressor = new ContextCompressor(model, 10, 1);
        List<Message> shortMessages = List.of(new UserMessage("短"));
        assertSame(shortMessages, compressor.compact(shortMessages));
        verifyNoInteractions(model);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("摘要")));
        List<Message> original = List.of(new UserMessage("历史".repeat(100)), new AssistantMessage("最近"));
        Map<String, Object> update = MessagesModelHook.beforeModelAction(new SummaryHook(compressor))
                .apply(new OverAllState(Map.of("messages", original)), RunnableConfig.builder().build()).join();
        assertInstanceOf(ReplaceAllWith.class, update.get("messages"));
    }

    @Test
    void compactsWhenOnlyTokenThresholdIsReached() {
        // 消息数很少但单条巨大：条数不能成为压缩门槛，否则一次超大输出就撑爆上下文
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("压缩后")));
        ContextCompressor compressor = new ContextCompressor(model, 10, 20);

        List<Message> original = List.of(
                new UserMessage("历史".repeat(100)),
                new AssistantMessage("输出".repeat(100)));
        List<Message> compacted = compressor.compact(original);

        assertNotSame(original, compacted);
        assertInstanceOf(SystemMessage.class, compacted.getFirst());
    }

    @Test
    void keepsRecentWindowWithinTokenBudget() {
        // 保留条数同时受条数上限与最近窗口预算约束：历史 3 条、阈值 10 时最多留 2 条
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("压缩后")));
        ContextCompressor compressor = new ContextCompressor(model, 10, 20);

        List<Message> original = List.of(
                new UserMessage("历史".repeat(100)),
                new AssistantMessage("中间".repeat(100)),
                new UserMessage("最近一条"));
        List<Message> compacted = compressor.compact(original);

        assertNotSame(original, compacted);
        assertSame(original.getLast(), compacted.getLast());
        assertTrue(compacted.size() < original.size() + 1);
    }

    @Test
    void needsCompactionMatchesActualCompaction() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response("压缩后")));
        ContextCompressor compressor = new ContextCompressor(model, 10, 1);
        List<Message> original = List.of(new UserMessage("历史".repeat(100)), new AssistantMessage("最近"));

        // 调用方先在压缩前问一次，再真正压缩；两次判断必须一致
        assertTrue(compressor.needsCompaction(original));
        assertNotSame(original, compressor.compact(original));
    }

    @Test
    void needsCompactionIsFalseWhenNothingCanBeCompacted() {
        ChatModel model = mock(ChatModel.class);
        ContextCompressor compactLarge = new ContextCompressor(model, 10, 1);
        // 只剩一条：那一条就是本轮自己的内容，没有可压的空间
        assertFalse(compactLarge.needsCompaction(List.of(new UserMessage("历史".repeat(100)))));
        // 远低于阈值
        ContextCompressor compactHigh = new ContextCompressor(model, 500000, 20);
        assertFalse(compactHigh.needsCompaction(List.of(new UserMessage("短"), new AssistantMessage("也短"))));
        verifyNoInteractions(model);
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
