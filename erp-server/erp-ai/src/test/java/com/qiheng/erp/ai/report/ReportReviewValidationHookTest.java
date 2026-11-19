package com.qiheng.erp.ai.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportReviewValidationHookTest {
    private final ReportReviewValidationHook hook = new ReportReviewValidationHook(new ObjectMapper());

    @Test
    void acceptsConflictingScoreAndReferenceForGraphDecision() throws Exception {
        var review = hook.parse("""
                {"score":9,"strengths":[],"hasReferenceIssue":true,
                 "issues":[{"problem":"引用错","reason":"编号不一致","suggest":"核对编号"}]}
                """);
        assertThat(review.score()).isEqualTo(9);
        assertThat(review.hasReferenceIssue()).isTrue();
        assertThat(review.issues().getFirst().suggest()).isEqualTo("核对编号");
    }

    @Test
    void rejectsWrongTypesMissingIssuesAndTrailingTokens() {
        assertThatThrownBy(() -> hook.parse("{\"score\":\"9\",\"strengths\":[],\"hasReferenceIssue\":false,\"issues\":[]}"))
                .hasMessageContaining("审核分数");
        assertThatThrownBy(() -> hook.parse("{\"score\":7,\"strengths\":[],\"hasReferenceIssue\":false,\"issues\":[]}"))
                .hasMessageContaining("必须说明问题");
        assertThatThrownBy(() -> hook.parse("{\"score\":9,\"strengths\":[],\"hasReferenceIssue\":false,\"issues\":[]} trailing"))
                .hasMessageContaining("JSON 格式错误");
    }

    @Test
    void retriesAtMostThreeTimesAndDoesNotCheckToolCalls() {
        AtomicInteger count = new AtomicInteger();
        var config = RunnableConfig.builder().threadId("review-test")
                .addMetadata(ReportReviewValidationHook.REPAIR_COUNT, count).build();
        var toolCall = AssistantMessage.builder().toolCalls(List.of(
                new AssistantMessage.ToolCall("call-1", "function", "readDataset", "{}"))).build();
        hook.afterModel(List.of(toolCall), config);
        assertThat(count).hasValue(0);
        for (int attempt = 0; attempt < 4; attempt++) {
            hook.afterModel(List.of(new AssistantMessage("不是 JSON")), config);
        }
        assertThat(count).hasValue(3);
    }
}
