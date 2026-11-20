package com.qiheng.erp.ai.agent.context;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.qiheng.erp.ai.config.properties.AiContextProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 压缩 hook 的失败边界：只有摘要完整可用时才替换历史，任何异常都保留原文。
 *
 * @author Li
 * @since 2026-11-20
 */
class ContextCompressionHookTest {

    private static final String SUMMARY = "## 目标\n分析库存\n## 工作状态\n已完成\n## 下一步\n继续";
    private static final String INVALID = "没有按模板输出";

    @Test
    void belowThresholdLeavesMessagesUntouched() throws Exception {
        var model = mock(ChatModel.class);
        var hook = hook(model, properties(100_000, 12, 2, false));
        var messages = history();

        AgentCommand command = hook.beforeModel(messages, config());

        assertThat(compressed(command)).isNull();
        verifyNoInteractions(model);
    }

    @Test
    void compressesToSummaryAndRecentMessages() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(SUMMARY, "stop"));
        var hook = hook(model, properties(100, 12, 2, false));
        var messages = history();

        AgentCommand command = hook.beforeModel(messages, config());
        List<Message> result = compressed(command);

        assertThat(result).hasSize(3);
        assertThat(result.getFirst()).isInstanceOf(UserMessage.class);
        assertThat(result.getFirst().getText()).startsWith(ContextCompressionHook.SUMMARY_PREFIX);
        assertThat(result.get(1)).isSameAs(messages.get(4));
        assertThat(result.get(2)).isSameAs(messages.get(5));
        verify(model, times(1)).call(any(Prompt.class));
    }

    @Test
    void keepsOriginalMessagesWhenSummaryFails() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("boom"));
        var hook = hook(model, properties(100, 12, 2, false));

        AgentCommand command = hook.beforeModel(history(), config());

        assertThat(compressed(command)).isNull();
    }

    @Test
    void retriesOnceThenGivesUpWhenTemplateIsMissing() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(INVALID, "stop"));
        var hook = hook(model, properties(100, 12, 2, false));

        AgentCommand command = hook.beforeModel(history(), config());

        assertThat(compressed(command)).isNull();
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test
    void secondAttemptCanSucceed() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(INVALID, "stop"), response(SUMMARY, "stop"));
        var hook = hook(model, properties(100, 12, 2, false));

        AgentCommand command = hook.beforeModel(history(), config());

        assertThat(compressed(command)).hasSize(3);
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test
    void truncatedOutputIsRetried() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(SUMMARY, "length"), response(SUMMARY, "stop"));
        var hook = hook(model, properties(100, 12, 2, false));

        AgentCommand command = hook.beforeModel(history(), config());

        assertThat(compressed(command)).hasSize(3);
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test
    void cutoffNeverSplitsToolPair() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(SUMMARY, "stop"));
        var hook = hook(model, properties(100, 100, 1, false));
        var messages = toolHistory();

        AgentCommand command = hook.beforeModel(messages, config());
        List<Message> result = compressed(command);

        assertThat(result).isNotNull();
        assertThat(result).noneMatch(message -> message instanceof ToolResponseMessage);
        assertThat(result.getFirst().getText()).startsWith(ContextCompressionHook.SUMMARY_PREFIX);
    }

    @Test
    void firstUserMessageCanBeKept() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(SUMMARY, "stop"));
        var hook = hook(model, properties(100, 12, 2, true));
        var messages = history();

        AgentCommand command = hook.beforeModel(messages, config());
        List<Message> result = compressed(command);

        assertThat(result).isNotNull();
        assertThat(result.getFirst()).isSameAs(messages.getFirst());
        assertThat(result.get(1).getText()).startsWith(ContextCompressionHook.SUMMARY_PREFIX);
    }

    @Test
    void retryReminderExplainsMissingHeadings() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(INVALID, "stop"), response(SUMMARY, "stop"));
        var hook = hook(model, properties(100, 12, 2, false));

        hook.beforeModel(history(), config());

        var captor = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(captor.capture());
        String reminder = captor.getAllValues().get(1).getInstructions().getLast().getText();
        assertThat(reminder).contains("缺少标题");
    }

    @Test
    void retryReminderExplainsTruncation() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(SUMMARY, "length"), response(SUMMARY, "stop"));
        var hook = hook(model, properties(100, 12, 2, false));

        hook.beforeModel(history(), config());

        var captor = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(captor.capture());
        String reminder = captor.getAllValues().get(1).getInstructions().getLast().getText();
        assertThat(reminder).contains("截断");
    }

    private static ContextCompressionHook hook(ChatModel model, AiContextProperties properties) {
        return new ContextCompressionHook(model, properties, "system prompt", null);
    }

    private static AiContextProperties properties(int maxTokens, int keepTokens, int minKeep,
                                                  boolean keepFirstUser) {
        return new AiContextProperties(true, maxTokens, keepTokens, minKeep, keepFirstUser,
                "flash", 60, 8000, 10_000, 100, 200);
    }

    private static RunnableConfig config() {
        return RunnableConfig.builder().threadId("compression-test").build();
    }

    private static ChatResponse response(String text, String finishReason) {
        var metadata = ChatGenerationMetadata.builder().finishReason(finishReason).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text), metadata)));
    }

    private static List<Message> history() {
        return List.of(
                new UserMessage("第一轮：" + "内容".repeat(100)),
                new AssistantMessage("第一轮回复：" + "内容".repeat(100)),
                new UserMessage("第二轮：" + "内容".repeat(100)),
                new AssistantMessage("第二轮回复：" + "内容".repeat(100)),
                new UserMessage("继续"),
                new AssistantMessage("好的"));
    }

    private static List<Message> toolHistory() {
        var call = new AssistantMessage.ToolCall("call-1", "function", "query_sql_data", "{\"goal\":\"库存\"}");
        return List.of(
                new UserMessage("分析库存"),
                AssistantMessage.builder().content("先查数据").toolCalls(List.of(call)).build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "query_sql_data",
                                "A".repeat(3000) + "END")))
                        .build(),
                new AssistantMessage("查到了"),
                new UserMessage("继续"),
                new AssistantMessage("好的"));
    }

    /** 通过反射读取命令中的消息，框架没有公开读取方法。 */
    @SuppressWarnings("unchecked")
    private static List<Message> compressed(AgentCommand command) throws ReflectiveOperationException {
        Field field = AgentCommand.class.getDeclaredField("messages");
        field.setAccessible(true);
        return (List<Message>) field.get(command);
    }
}