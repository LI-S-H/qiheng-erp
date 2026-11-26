package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback.FeedbackResult;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 探针：核实 HITL Hook 与 ToolInterceptor 的执行先后。
 *
 * <p>人工确认 Hook 是 AFTER_MODEL 图节点，工具拦截器在工具节点内执行。探针通过真实图运行记录
 * 事件顺序，回答“拦截器能否在 HITL 中断前校验参数”。</p>
 *
 * @author Li
 * @since 2026-11-26
 */
class HitlInterceptorOrderProbeTest {

    private static final String TOOL_NAME = "probe_hitl_order";

    /** 记录工具节点内外的实际执行顺序。 */
    private static final List<String> EVENTS = new CopyOnWriteArrayList<>();

    /** 探针工具：只有真正进入工具节点才会写入事件。 */
    static class ProbeTool {

        /**
         * 返回固定结果，同时记录工具方法已执行。
         *
         * @param value 任意内容
         * @return 固定结果
         */
        @Tool(name = TOOL_NAME, description = "核实 HITL 与工具拦截器先后顺序的探针工具")
        public String probe(@ToolParam(description = "任意内容") String value) {
            EVENTS.add("tool");
            return "工具已执行";
        }
    }

    /** 探针拦截器：记录工具节点内拦截器的前后位置。 */
    static class ProbeInterceptor extends ToolInterceptor {

        @Override
        public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
            EVENTS.add("interceptor:before:" + request.getToolName());
            ToolCallResponse response = handler.call(request);
            EVENTS.add("interceptor:after:" + request.getToolName());
            return response;
        }

        @Override
        public String getName() {
            return "probe_interceptor";
        }
    }

    /**
     * 首次调用应停在 HITL 节点且不进入工具节点；批准恢复后才按“拦截器 → 工具 → 拦截器”执行。
     */
    @Test
    void interceptorRunsInsideToolNodeAfterHitlInterrupt() throws Exception {
        EVENTS.clear();
        var model = mock(ChatModel.class);
        var turns = new AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            if (turns.getAndIncrement() == 0) {
                var call = new AssistantMessage.ToolCall("probe-call-1", "function", TOOL_NAME,
                        "{\"value\":\"x\"}");
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("准备调用探针工具").toolCalls(List.of(call)).build())));
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("探针任务完成"))));
        });

        var saver = new MemorySaver();
        var agent = ReactAgent.builder()
                .name("hitl_interceptor_order_probe")
                .model(model)
                .systemPrompt("你是测试用主智能体")
                .methodTools(new ProbeTool())
                .hooks(HumanInTheLoopHook.builder()
                        .approvalOn(TOOL_NAME, ToolConfig.builder().build())
                        .build())
                .interceptors(new ProbeInterceptor())
                .saver(saver)
                .releaseThread(false)
                .build();

        var config = RunnableConfig.builder().threadId("probe:hitl-interceptor-order").build();

        // 第一次调用：应在 AFTER_MODEL 的 HITL 节点中断，工具节点和拦截器都不执行。
        try {
            agent.call("请调用探针工具", config);
        } catch (Exception ignored) {
            // 中断时没有最终 AssistantMessage，框架可能直接抛异常；以检查点和事件为准。
        }
        var interrupted = saver.get(config).orElseThrow();
        System.out.println("PROBE_FIRST_NEXT_NODE=" + interrupted.getNextNodeId());
        System.out.println("PROBE_FIRST_EVENTS=" + EVENTS);
        assertThat(interrupted.getNextNodeId()).contains("HITL");
        assertThat(EVENTS).isEmpty();

        // 模拟用户在确认卡片上批准：带反馈元数据恢复。
        var feedback = InterruptionMetadata.builder()
                .addToolFeedback(InterruptionMetadata.ToolFeedback.builder()
                        .id("probe-call-1")
                        .name(TOOL_NAME)
                        .arguments("{\"value\":\"x\"}")
                        .result(FeedbackResult.APPROVED)
                        .build())
                .build();
        var resumeConfig = RunnableConfig.builder(config)
                .addMetadata(RunnableConfig.HUMAN_FEEDBACK_METADATA_KEY, feedback)
                .build();
        var resumed = agent.call("继续", resumeConfig);

        System.out.println("PROBE_RESUMED_TEXT=" + resumed.getText());
        System.out.println("PROBE_RESUMED_EVENTS=" + EVENTS);
        assertThat(resumed.getText()).isEqualTo("探针任务完成");
        assertThat(EVENTS).containsExactly(
                "interceptor:before:" + TOOL_NAME,
                "tool",
                "interceptor:after:" + TOOL_NAME);
    }
}