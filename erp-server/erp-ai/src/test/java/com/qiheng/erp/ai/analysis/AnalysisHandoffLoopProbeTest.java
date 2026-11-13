package com.qiheng.erp.ai.analysis;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;
import com.qiheng.erp.ai.trace.AiAgentTraceRecorder;

/** 独立探针：验证流水顺序、图内跳转和修正次数，不接入生产智能体。 */
class AnalysisHandoffLoopProbeTest {
    private static final String RETRIES = "probeHandoffRetries";
    private static final String FEEDBACK = "最终交接结果的 JSON 格式错误，请重新生成。";
    private static final String VALID = "{\"completion\":\"COMPLETED\",\"summary\":\"库存8条\"}";

    static final class StockTool {
        final AtomicInteger calls = new AtomicInteger();

        @Tool(name = "probe_stock", description = "获取测试库存记录数")
        public String stock() {
            calls.incrementAndGet();
            return "库存8条";
        }
    }

    /** 检查跳转结束时框架是否仍执行智能体结束 Hook。 */
    @HookPositions(HookPosition.AFTER_AGENT)
    static final class FinishHook extends com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesAgentHook {
        private final List<String> events;
        private ReactAgent agent;

        FinishHook(List<String> events) {
            this.events = events;
        }

        @Override
        public AgentCommand afterAgent(List<Message> messages, RunnableConfig config) {
            events.add("afterAgent");
            return new AgentCommand(null);
        }

        @Override
        public int getOrder() {
            return 0;
        }

        @Override
        public ReactAgent getAgent() {
            return agent;
        }

        @Override
        public void setAgent(ReactAgent agent) {
            this.agent = agent;
        }

        @Override
        public String getName() {
            return "probe_after_agent";
        }
    }

    /** 仅检查候选最终消息；修正时跳回模型并保留现有消息。 */
    @HookPositions(HookPosition.AFTER_MODEL)
    static final class ValidationHook extends MessagesModelHook {
        private final List<String> events;
        private final boolean endOnValid;
        private final int order;
        private final ObjectMapper mapper = new ObjectMapper();

        ValidationHook(List<String> events) {
            this(events, false);
        }

        ValidationHook(List<String> events, boolean endOnValid) {
            this(events, endOnValid, -1);
        }

        ValidationHook(List<String> events, boolean endOnValid, int order) {
            this.events = events;
            this.endOnValid = endOnValid;
            this.order = order;
        }

        @Override
        public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
            events.add("validate");
            var last = (AssistantMessage) messages.getLast();
            var attempts = (AtomicInteger) config.metadata(RETRIES).orElseThrow();
            if (!last.getToolCalls().isEmpty()) {
                if (attempts.get() > 0) {
                    throw new IllegalStateException("修正阶段不允许再次调用工具");
                }
                return new AgentCommand(null);
            }
            try {
                var output = mapper.readValue(last.getText(), AnalysisHandoff.class);
                if (output != null && output.summary() != null && !output.summary().isBlank()
                        && List.of("COMPLETED", "PARTIAL", "NEED_INFO").contains(output.completion())) {
                    if (endOnValid && attempts.get() > 0) {
                        return new AgentCommand(JumpTo.end, null);
                    }
                    return new AgentCommand(null);
                }
            } catch (Exception ignored) {
                // 格式错误按统一路径要求模型重新生成。
            }
            if (attempts.getAndIncrement() >= 3) {
                throw new IllegalStateException("格式修正次数已用完");
            }
            return new AgentCommand(JumpTo.model, List.of(new UserMessage(FEEDBACK)), UpdatePolicy.APPEND);
        }

        @Override
        public List<JumpTo> canJumpTo() {
            return List.of(JumpTo.model, JumpTo.end);
        }

        @Override
        public int getOrder() {
            return order;
        }

        @Override
        public String getName() {
            return "probe_handoff_validation";
        }
    }

    /** 实际流水 Hook 先记录调用，再修复错误；下一轮保留工具结果。 */
    @Test
    @Timeout(30)
    void recordsThenRetriesWithContext() throws Exception {
        var events = new ArrayList<String>();
        var traces = mock(AiAgentTraceMapper.class);
        doAnswer(call -> {
            events.add("trace:" + call.getArgument(4));
            return null;
        }).when(traces).insert(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString());
        var model = mock(ChatModel.class);
        var turns = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            int turn = turns.getAndIncrement();
            if (turn == 0) {
                var request = new AssistantMessage.ToolCall("stock-1", "function", "probe_stock", "{}");
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("先查询库存").toolCalls(List.of(request)).build())));
            }
            if (turn == 1) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("不是 JSON"))));
            }
            if (turn > 2) {
                throw new IllegalStateException("修正成功后模型被重复调用");
            }
            String context = prompt.getInstructions().toString();
            System.out.println("REPAIR_CONTEXT=" + context);
            assertThat(context).contains("分析库存", "库存8条", "不是 JSON", FEEDBACK);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(VALID))));
        });
        var tool = new StockTool();
        var agent = ReactAgent.builder().name("handoff_loop_probe").model(model).systemPrompt("分析库存")
                .methodTools(tool).hooks(new AnalysisTraceHook(), new ValidationHook(events, true), new FinishHook(events)).build();
        var config = RunnableConfig.builder().threadId("loop-probe")
                .addMetadata(AnalysisTraceHook.RECORDER_KEY,
                        new AiAgentTraceRecorder(traces, new ObjectMapper(), new AiAgentTraceRecorder.Scope(1, 2, "loop-probe", "ANALYSIS")))
                .addMetadata(RETRIES, new AtomicInteger()).build();

        assertThat(agent.call("分析库存", config).getText()).isEqualTo(VALID);
        assertThat(tool.calls).hasValue(1);
        assertThat(turns).hasValue(3);
        assertThat(events).containsExactly("trace:MODEL_OUTPUT", "trace:TOOL_CALL", "validate",
                "trace:TOOL_RESULT", "validate", "validate", "afterAgent");
        System.out.println("LOOP_EVENTS=" + events);
    }

    /** 验证仅返回无跳转的指令时，旧跳转状态是否会继续生效。 */
    @Test
    @Timeout(30)
    void observesStaleJumpWithoutExplicitEnd() throws Exception {
        var model = mock(ChatModel.class);
        var turns = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(call -> {
            int turn = turns.getAndIncrement();
            if (turn > 1) {
                throw new IllegalStateException("修正成功后仍被重复调用");
            }
            String text = turn == 0 ? "不是 JSON" : VALID;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        });
        var agent = ReactAgent.builder().name("stale_jump_probe").model(model).systemPrompt("分析库存")
                .hooks(new ValidationHook(new ArrayList<>())).build();
        var config = RunnableConfig.builder().threadId("stale-jump").addMetadata(RETRIES, new AtomicInteger()).build();
        assertThatThrownBy(() -> agent.call("分析库存", config)).isNotNull();
        assertThat(turns.get()).isGreaterThan(2);
        System.out.println("STALE_JUMP_CALLS=" + turns.get());
    }

    /** 同一智能体并发运行不同任务时，修正次数互不影响。 */
    @Test
    @Timeout(30)
    void isolatesConcurrentRepairCounts() throws Exception {
        var model = mock(ChatModel.class);
        var calls = new java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String context = prompt.getInstructions().toString();
            String task = context.contains("并发任务A") ? "A" : "B";
            int turn = calls.computeIfAbsent(task, key -> new AtomicInteger()).getAndIncrement();
            String output = turn < ("A".equals(task) ? 2 : 1) ? "不是 JSON" : VALID;
            return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
        });
        var agent = ReactAgent.builder().name("parallel_probe").model(model).systemPrompt("分析库存")
                .hooks(new ValidationHook(java.util.Collections.synchronizedList(new ArrayList<>()), true)).build();
        var firstCount = new AtomicInteger();
        var secondCount = new AtomicInteger();
        var firstConfig = RunnableConfig.builder().threadId("parallel-a").addMetadata(RETRIES, firstCount).build();
        var secondConfig = RunnableConfig.builder().threadId("parallel-b").addMetadata(RETRIES, secondCount).build();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> agent.call("并发任务A", firstConfig).getText());
            var second = pool.submit(() -> agent.call("并发任务B", secondConfig).getText());
            assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(VALID);
            assertThat(second.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(VALID);
            assertThat(firstCount).hasValue(2);
            assertThat(secondCount).hasValue(1);
            System.out.println("CONCURRENT_REPAIRS A=" + firstCount + " B=" + secondCount);
        } finally {
            pool.shutdownNow();
        }
    }

    /** 初次输出加三次修正仍无效时必须停止。 */
    @Test
    @Timeout(30)
    void stopsAfterThreeRepairs() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("不是 JSON")))));
        var attempts = new AtomicInteger();
        var agent = ReactAgent.builder().name("limit_probe").model(model).systemPrompt("分析库存")
                .hooks(new ValidationHook(new ArrayList<>())).build();
        var config = RunnableConfig.builder().threadId("limit-probe").addMetadata(RETRIES, attempts).build();

        assertThatThrownBy(() -> agent.call("分析库存", config)).isNotNull();
        verify(model, times(4)).call(any(Prompt.class));
        System.out.println("LIMIT_MODEL_CALLS=4 REPAIRS=3");
    }

    /** 进入修正阶段后再次请求工具时，不能再次执行该工具。 */
    @Test
    @Timeout(30)
    void blocksToolsDuringRepair() throws Exception {
        var model = mock(ChatModel.class);
        var traces = mock(AiAgentTraceMapper.class);
        var call = new AssistantMessage.ToolCall("stock-1", "function", "probe_stock", "{}");
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call)).build()))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("不是 JSON")))),
                new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call)).build()))));
        var tool = new StockTool();
        var agent = ReactAgent.builder().name("tool_guard_probe").model(model).systemPrompt("分析库存")
                .methodTools(tool).hooks(new AnalysisTraceHook(), new ValidationHook(new ArrayList<>())).build();
        var config = RunnableConfig.builder().threadId("tool-guard").addMetadata(RETRIES, new AtomicInteger())
                .addMetadata(AnalysisTraceHook.RECORDER_KEY,
                        new AiAgentTraceRecorder(traces, new ObjectMapper(), new AiAgentTraceRecorder.Scope(1, 2, "tool-guard", "ANALYSIS")))
                .build();

        assertThatThrownBy(() -> agent.call("分析库存", config)).isNotNull();
        assertThat(tool.calls).hasValue(1);
        verify(traces, times(2)).insert(eq(1L), eq(2L), eq("tool-guard"), eq("ANALYSIS"), eq("TOOL_CALL"), anyString());
        verify(traces, times(1)).insert(eq(1L), eq(2L), eq("tool-guard"), eq("ANALYSIS"), eq("TOOL_RESULT"), anyString());
        System.out.println("REPAIR_TOOL_EXECUTIONS=" + tool.calls.get());
        System.out.println("REPAIR_TRACE_TOOL_CALLS=2 TOOL_RESULTS=1");
    }

    /** 检查先执行时，修正阶段被拒绝的工具请求不会进入普通调用流水。 */
    @Test
    @Timeout(30)
    void validationFirstAvoidsRecordingBlockedToolCall() throws Exception {
        var model = mock(ChatModel.class);
        var traces = mock(AiAgentTraceMapper.class);
        var call = new AssistantMessage.ToolCall("stock-1", "function", "probe_stock", "{}");
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call)).build()))),
                new ChatResponse(List.of(new Generation(new AssistantMessage("不是 JSON")))),
                new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call)).build()))));
        var events = new ArrayList<String>();
        doAnswer(invocation -> {
            events.add("trace:" + invocation.getArgument(4));
            return null;
        }).when(traces).insert(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString());
        var tool = new StockTool();
        var agent = ReactAgent.builder().name("validation_first_probe").model(model).systemPrompt("分析库存")
                .methodTools(tool).hooks(new AnalysisTraceHook(), new ValidationHook(events, false, 1)).build();
        var config = RunnableConfig.builder().threadId("validation-first").addMetadata(RETRIES, new AtomicInteger())
                .addMetadata(AnalysisTraceHook.RECORDER_KEY,
                        new AiAgentTraceRecorder(traces, new ObjectMapper(), new AiAgentTraceRecorder.Scope(1, 2, "validation-first", "ANALYSIS")))
                .build();

        assertThatThrownBy(() -> agent.call("分析库存", config)).isNotNull();
        assertThat(tool.calls).hasValue(1);
        verify(traces, times(1)).insert(eq(1L), eq(2L), eq("validation-first"), eq("ANALYSIS"), eq("TOOL_CALL"), anyString());
        verify(traces, times(1)).insert(eq(1L), eq(2L), eq("validation-first"), eq("ANALYSIS"), eq("TOOL_RESULT"), anyString());
        System.out.println("VALIDATION_FIRST_EVENTS=" + events);
    }
}
