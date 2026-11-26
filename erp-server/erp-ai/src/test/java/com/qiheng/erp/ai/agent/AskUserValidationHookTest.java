package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.clarification.ClarificationRules;
import com.qiheng.erp.ai.clarification.ClarificationTexts;
import com.qiheng.erp.ai.clarification.ClarificationTraceSink;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 追问参数前置校验 Hook 测试。
 *
 * @author Li
 * @since 2026-11-26
 */
class AskUserValidationHookTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final FakeTraceSink sink = new FakeTraceSink();
    private final AskUserValidationHook hook = new AskUserValidationHook(mapper);

    /** 合法参数不追加任何消息，并清空计数。 */
    @Test
    void validArgumentsPassThrough() {
        var result = run(config("run-valid"), ask("call-1", validArguments()));

        assertThat(result).isEmpty();
        assertThat(sink.validationFailures).isEmpty();
    }

    /** 前三次非法只追加错误 ToolResponse，并写流水。 */
    @Test
    void firstThreeFailuresReturnToModel() throws Exception {
        for (int attempt = 1; attempt <= AskUserValidationHook.MAX_ATTEMPTS; attempt++) {
            var result = run(config("run-retry"), ask("call-" + attempt, invalidArguments()));

            assertThat(result).doesNotContainKey("jump_to");
            assertThat((List<?>) result.get("messages")).hasSize(1);
            var response = (ToolResponseMessage) ((List<?>) result.get("messages")).getFirst();
            var body = mapper.readTree(response.getResponses().getFirst().responseData());
            assertThat(body.path("status").asText()).isEqualTo("invalid_arguments");
            assertThat(body.path("errors").isArray()).isTrue();
            assertThat(body.path("errors").get(0).path("path").asText()).isNotEmpty();
        }

        assertThat(sink.validationFailures).hasSize(3);
        assertThat(sink.toolResults).isEmpty();
        assertThat(sink.failed.get()).isFalse();
    }

    /** 第三次内修正后计数清零，后续非法重新从 1 开始。 */
    @Test
    void resetsCounterAfterValidArgument() {
        run(config("run-reset"), ask("call-1", invalidArguments()));
        run(config("run-reset"), ask("call-2", invalidArguments()));
        assertThat(run(config("run-reset"), ask("call-3", validArguments()))).isEmpty();

        var result = run(config("run-reset"), ask("call-4", invalidArguments()));

        assertThat(result).doesNotContainKey("jump_to");
        assertThat((List<?>) result.get("messages")).hasSize(1);
    }

    /** 第四次非法追加错误回执和最终说明，并跳转 END。 */
    @Test
    void fourthFailureEndsGraph() {
        Map<String, Object> result = Map.of();
        for (int attempt = 1; attempt <= AskUserValidationHook.MAX_ATTEMPTS + 1; attempt++) {
            result = run(config("run-terminal"), ask("call-" + attempt, invalidArgumentsWithQuestion()));
        }

        assertThat(result.get("jump_to")).isEqualTo(JumpTo.end);
        assertThat(result.get(AskUserValidationHook.FAILED_STATE_KEY)).isEqualTo("run-terminal");
        var messages = (List<?>) result.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).isInstanceOf(ToolResponseMessage.class);
        assertThat(messages.get(1)).isInstanceOf(AssistantMessage.class);
        assertThat(((AssistantMessage) messages.get(1)).getText())
                .contains(ClarificationTexts.TERMINAL_ERROR_HEADER)
                .contains("- 问题1：查哪个仓库？");
        assertThat(((AssistantMessage) messages.get(1)).getToolCalls()).isEmpty();
        assertThat(sink.failed.get()).isTrue();
        assertThat(sink.toolResults).hasSize(1);
    }

    /** 非法错误必须包含具体 JSON 路径。 */
    @Test
    void errorJsonContainsFieldPaths() throws Exception {
        var result = run(config("run-path"), ask("call-path", invalidArguments()));
        var response = (ToolResponseMessage) ((List<?>) result.get("messages")).getFirst();
        var body = mapper.readTree(response.getResponses().getFirst().responseData());

        assertThat(body.path("errors").toString()).contains("questions[0].question");
        assertThat(body.path("errors").toString()).contains("不能为空");
    }

    /** 没有 ask_user 调用时清空该运行计数。 */
    @Test
    void clearsCounterWhenNoAskUserCall() {
        run(config("run-clear"), ask("call-1", invalidArguments()));
        run(config("run-clear"), new AssistantMessage("直接回答"));

        var result = run(config("run-clear"), ask("call-2", invalidArguments()));

        assertThat(result).doesNotContainKey("jump_to");
        assertThat((List<?>) result.get("messages")).hasSize(1);
    }

    private RunnableConfig config(String runId) {
        return RunnableConfig.builder()
                .threadId("thread-" + runId)
                .addMetadata("runId", runId)
                .addMetadata("messageRecorder", sink)
                .build();
    }

    private Map<String, Object> run(RunnableConfig config, AssistantMessage message) {
        var state = new OverAllState(Map.of("messages", List.of(message)));
        return hook.afterModel(state, config).join();
    }

    private AssistantMessage ask(String callId, String arguments) {
        return AssistantMessage.builder()
                .content("需要补充条件")
                .toolCalls(List.of(new AssistantMessage.ToolCall(callId, "function", "ask_user", arguments)))
                .build();
    }

    private String validArguments() {
        return """
                {"questions":[{"id":"warehouse","question":"查哪个仓库？","type":"SINGLE_CHOICE","options":["一号仓"]}]}
                """;
    }

    private String invalidArguments() {
        return """
                {"questions":[{"id":"warehouse","type":"SINGLE_CHOICE","options":[]}]}
                """;
    }

    private String invalidArgumentsWithQuestion() {
        return """
                {"questions":[{"id":"warehouse","question":"查哪个仓库？","type":"SINGLE_CHOICE","options":[]}]}
                """;
    }

    /** 记录流水调用，不触碰数据库。 */
    static class FakeTraceSink implements ClarificationTraceSink {

        final List<List<ClarificationRules.FieldError>> validationFailures = new ArrayList<>();
        final List<String> toolResults = new ArrayList<>();
        final AtomicBoolean failed = new AtomicBoolean();

        @Override
        public void traceAskUserValidationFailure(String callId, String name, String arguments,
                                                  List<ClarificationRules.FieldError> errors) {
            validationFailures.add(errors);
        }

        @Override
        public void traceAskUserToolResult(String callId, String name, String responseData) {
            toolResults.add(responseData);
        }

        @Override
        public void markClarificationValidationFailed() {
            failed.set(true);
        }
    }
}