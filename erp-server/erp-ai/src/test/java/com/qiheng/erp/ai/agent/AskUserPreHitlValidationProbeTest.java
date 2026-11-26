package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.ModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 探针：验证能否在 HITL 之前拦截 ask_user 参数，并把格式错误直接交回模型。
 *
 * @author Li
 * @since 2026-11-26
 */
class AskUserPreHitlValidationProbeTest {

    private static final String TOOL_NAME = "ask_user_probe";

    private static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /** 探针工具：正常路径不应执行到工具方法。 */
    static class ProbeAskUserTool {

        /**
         * 记录工具方法是否被调用。
         *
         * @param questions 模拟问题参数
         * @return 固定结果
         */
        @Tool(name = TOOL_NAME, description = "验证 HITL 前置校验的探针追问工具")
        public String ask(@ToolParam(description = "问题数组") String questions) {
            EVENTS.add("tool");
            return "工具不应被调用";
        }
    }

    /** 在 HITL 之前运行：参数非法时写错误 ToolResponse，让图回到模型节点。 */
    @HookPositions({HookPosition.AFTER_MODEL})
    static class ProbeValidationHook extends ModelHook {

        @Override
        public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
            @SuppressWarnings("unchecked")
            List<Message> messages = (List<Message>) state.value("messages").orElse(List.of());
            if (messages.isEmpty() || !(messages.getLast() instanceof AssistantMessage assistant)
                    || assistant.getToolCalls().isEmpty()) {
                return CompletableFuture.completedFuture(Map.of());
            }
            var ask = assistant.getToolCalls().stream()
                    .filter(call -> TOOL_NAME.equals(call.name()))
                    .findFirst();
            if (ask.isEmpty() || valid(ask.get().arguments())) {
                return CompletableFuture.completedFuture(Map.of());
            }
            EVENTS.add("validation:invalid");
            var response = ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            ask.get().id(), ask.get().name(), "追问格式错误：questions 缺少必填字段，请修正后重试。")))
                    .build();
            return CompletableFuture.completedFuture(Map.of("messages", List.of(response)));
        }

        /** 探针只检查 questions 是否为数组且包含 question 文本。 */
        private boolean valid(String arguments) {
            return arguments != null && arguments.contains("\"question\"");
        }

        @Override
        public String getName() {
            return "probe_ask_user_validation";
        }
    }

    /** 连续失败后的终止校验 Hook：写配对响应与最终说明，并跳转 END。 */
    @HookPositions({HookPosition.AFTER_MODEL})
    static class TerminalValidationHook extends ModelHook {

        @Override
        public CompletableFuture<Map<String, Object>> afterModel(OverAllState state, RunnableConfig config) {
            @SuppressWarnings("unchecked")
            List<Message> messages = (List<Message>) state.value("messages").orElse(List.of());
            if (messages.isEmpty() || !(messages.getLast() instanceof AssistantMessage assistant)
                    || assistant.getToolCalls().isEmpty()) {
                return CompletableFuture.completedFuture(Map.of());
            }
            var ask = assistant.getToolCalls().stream()
                    .filter(call -> TOOL_NAME.equals(call.name()))
                    .findFirst();
            if (ask.isEmpty() || valid(ask.get().arguments())) {
                return CompletableFuture.completedFuture(Map.of());
            }
            EVENTS.add("validation:terminal");
            var response = ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            ask.get().id(), ask.get().name(), "追问参数连续格式错误，已停止重试。")))
                    .build();
            // 先配对工具调用，再追加最终说明，避免下一次对话出现未响应的 tool call。
            var finalMessage = new AssistantMessage("AI 连续多次无法生成有效追问，请重新描述需要补充的信息。");
            return CompletableFuture.completedFuture(Map.of(
                    "messages", List.of(response, finalMessage),
                    "jump_to", JumpTo.end));
        }

        private boolean valid(String arguments) {
            return arguments != null && arguments.contains("\"question\"");
        }

        @Override
        public String getName() {
            return "probe_terminal_validation";
        }
    }

    /**
     * 非法参数不会进入 HITL：错误响应回到模型，模型有机会立即修正。
     */
    @Test
    void invalidArgumentsReturnToModelBeforeHitl() throws Exception {
        EVENTS.clear();
        var model = mock(ChatModel.class);
        var turns = new AtomicInteger();
        var secondPrompt = new AtomicReference<String>();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            int turn = turns.getAndIncrement();
            if (turn == 0) {
                var call = new AssistantMessage.ToolCall("ask-bad", "function", TOOL_NAME,
                        "{\"questions\":[{\"id\":\"warehouse\"}]}");
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("需要补充条件").toolCalls(List.of(call)).build())));
            }
            secondPrompt.set(invocation.getArgument(0, Prompt.class).getInstructions().toString());
            return new ChatResponse(List.of(new Generation(new AssistantMessage("已改为直接回答"))));
        });

        var saver = new MemorySaver();
        var agent = ReactAgent.builder()
                .name("ask_user_pre_hitl_probe")
                .model(model)
                .systemPrompt("你是测试用主智能体")
                .methodTools(new ProbeAskUserTool())
                // 后注册的 AFTER_MODEL Hook 先执行，校验先于 HITL。
                .hooks(HumanInTheLoopHook.builder()
                                .approvalOn(TOOL_NAME, ToolConfig.builder().build())
                                .build(),
                        new ProbeValidationHook())
                .saver(saver)
                .releaseThread(false)
                .build();

        var config = RunnableConfig.builder().threadId("probe:ask-user-pre-hitl").build();
        var result = agent.call("帮我查库存", config);

        var checkpoint = saver.get(config).orElseThrow();
        System.out.println("PROBE_INVALID_TEXT=" + result.getText());
        System.out.println("PROBE_INVALID_TURNS=" + turns.get());
        System.out.println("PROBE_INVALID_EVENTS=" + EVENTS);
        System.out.println("PROBE_INVALID_NEXT_NODE=" + checkpoint.getNextNodeId());
        System.out.println("PROBE_INVALID_SECOND_PROMPT_HAS_ERROR="
                + (secondPrompt.get() != null && secondPrompt.get().contains("追问格式错误")));

        assertThat(result.getText()).isEqualTo("已改为直接回答");
        assertThat(turns).hasValue(2);
        assertThat(EVENTS).containsExactly("validation:invalid");
        assertThat(secondPrompt.get()).contains("追问格式错误");
    }

    /**
     * 合法参数仍然正常进入 HITL，说明校验 Hook 没有破坏原有确认链路。
     */
    @Test
    void validArgumentsStillInterruptAtHitl() throws Exception {
        EVENTS.clear();
        var model = mock(ChatModel.class);
        var turns = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            if (turns.getAndIncrement() == 0) {
                var call = new AssistantMessage.ToolCall("ask-good", "function", TOOL_NAME,
                        "{\"questions\":[{\"id\":\"warehouse\",\"question\":\"查哪个仓库？\",\"type\":\"TEXT\",\"options\":[]}]}");
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("需要补充条件").toolCalls(List.of(call)).build())));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("不应到这里"))));
        });

        var saver = new MemorySaver();
        var agent = ReactAgent.builder()
                .name("ask_user_pre_hitl_valid_probe")
                .model(model)
                .systemPrompt("你是测试用主智能体")
                .methodTools(new ProbeAskUserTool())
                .hooks(HumanInTheLoopHook.builder()
                                .approvalOn(TOOL_NAME, ToolConfig.builder().build())
                                .build(),
                        new ProbeValidationHook())
                .saver(saver)
                .releaseThread(false)
                .build();

        var config = RunnableConfig.builder().threadId("probe:ask-user-pre-hitl-valid").build();
        try {
            agent.call("帮我查库存", config);
        } catch (Exception ignored) {
            // HITL 中断时没有最终 AssistantMessage。
        }
        var checkpoint = saver.get(config).orElseThrow();
        System.out.println("PROBE_VALID_NEXT_NODE=" + checkpoint.getNextNodeId());
        System.out.println("PROBE_VALID_EVENTS=" + EVENTS);
        System.out.println("PROBE_VALID_TURNS=" + turns.get());

        assertThat(checkpoint.getNextNodeId()).contains("HITL");
        assertThat(EVENTS).isEmpty();
        assertThat(turns).hasValue(1);
    }

    /**
     * 连续失败后可以安全结束：工具调用与响应成对，最终说明可保存，图停在 END。
     */
    @Test
    void terminalFailureEndsGraphWithPairedMessages() throws Exception {
        EVENTS.clear();
        var model = mock(ChatModel.class);
        var turns = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            turns.incrementAndGet();
            var call = new AssistantMessage.ToolCall("ask-terminal", "function", TOOL_NAME,
                    "{\"questions\":[{\"id\":\"warehouse\"}]}");
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                    .content("需要补充条件").toolCalls(List.of(call)).build())));
        });

        var saver = new MemorySaver();
        var agent = ReactAgent.builder()
                .name("ask_user_terminal_probe")
                .model(model)
                .systemPrompt("你是测试用主智能体")
                .methodTools(new ProbeAskUserTool())
                .hooks(HumanInTheLoopHook.builder()
                                .approvalOn(TOOL_NAME, ToolConfig.builder().build())
                                .build(),
                        new TerminalValidationHook())
                .saver(saver)
                .releaseThread(false)
                .build();

        var config = RunnableConfig.builder().threadId("probe:ask-user-terminal").build();
        var result = agent.call("帮我查库存", config);
        var checkpoint = saver.get(config).orElseThrow();
        var messages = (List<?>) checkpoint.getState().get("messages");
        var last = messages.getLast();
        var secondLast = messages.get(messages.size() - 2);
        var thirdLast = messages.get(messages.size() - 3);

        System.out.println("PROBE_TERMINAL_TEXT=" + result.getText());
        System.out.println("PROBE_TERMINAL_TURNS=" + turns.get());
        System.out.println("PROBE_TERMINAL_EVENTS=" + EVENTS);
        System.out.println("PROBE_TERMINAL_NEXT_NODE=" + checkpoint.getNextNodeId());
        System.out.println("PROBE_TERMINAL_LAST_TYPES="
                + thirdLast.getClass().getSimpleName() + "," + secondLast.getClass().getSimpleName()
                + "," + last.getClass().getSimpleName());

        assertThat(result.getText()).isEqualTo("AI 连续多次无法生成有效追问，请重新描述需要补充的信息。");
        assertThat(turns).hasValue(1);
        assertThat(EVENTS).containsExactly("validation:terminal");
        assertThat(checkpoint.getNextNodeId()).isEqualTo("__END__");
        assertThat(thirdLast).isInstanceOf(AssistantMessage.class);
        assertThat(secondLast).isInstanceOf(ToolResponseMessage.class);
        assertThat(last).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) last).getToolCalls()).isEmpty();
    }
}