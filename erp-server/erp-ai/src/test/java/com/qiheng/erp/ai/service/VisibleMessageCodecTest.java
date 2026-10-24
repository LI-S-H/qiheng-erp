package com.qiheng.erp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.vo.VisibleMessageContent;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class VisibleMessageCodecTest {

    private final VisibleMessageCodec codec = new VisibleMessageCodec(new ObjectMapper());

    @Test
    void preservesTypedContentAndExactStringIdentifiers() {
        VisibleMessageContent content = new VisibleMessageContent("TEXT", Map.of(
                "text", "最近30天", "replyToMessageId", "9223372036854775806"));
        assertThat(codec.decode(codec.encode(content))).isEqualTo(content);
    }

    @Test
    void mapsLegacyPlanWithoutExposingInternalMetadata() {
        var decoded = codec.decode("""
                {"text":"需要补充","metadata":{"secret":"internal", "plan":{
                  "needs_clarification":true,"clarification_questions":[]}}}
                """);
        assertThat(decoded.type()).isEqualTo("CLARIFICATION");
        assertThat(decoded.data()).containsKeys("text", "plan", "questions");
        assertThat(decoded.data()).doesNotContainKey("secret");
    }

    @Test
    void missingLegacyRequestIdUsesEmptyStringAndSequenceRemainsExact() {
        var message = new com.qiheng.erp.ai.domain.entity.AiMessage()
                .setId(1L).setSeq(9223372036854775806L).setContent("旧消息");
        var view = codec.toView(message);
        assertThat(view.requestId()).isEmpty();
        assertThat(view.seq()).isEqualTo("9223372036854775806");
    }

    @Test
    void keepsPauseIdOnTypedClarificationAndDropsCheckpointId() {
        VisibleMessageContent content = new VisibleMessageContent("CLARIFICATION", Map.of(
                "text", "补充", "threadId", "t1", "pauseId", "p1", "checkpointId", "c1"));

        var decoded = codec.decode(codec.encode(content));

        assertThat(decoded.data()).containsEntry("pauseId", "p1").doesNotContainKey("checkpointId");
    }

    @Test
    void jsonNullContentBecomesEmptyText() {
        assertThat(codec.decode("null").data()).containsEntry("text", "");
    }

    @Test
    void unknownLegacyJsonDoesNotLeakRawObject() {
        assertThat(codec.decode("{\"internalError\":\"stacktrace\"}").data())
                .containsEntry("text", "");
        assertThat(codec.decode("旧纯文本").data()).containsEntry("text", "旧纯文本");
    }
}
