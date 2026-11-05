package com.qiheng.erp.ai.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class VisibleMessageCodecTest {
    private final VisibleMessageCodec codec = new VisibleMessageCodec(new ObjectMapper());

    @Test void readsTextAndUserQuestionTools() throws Exception {
        var message = new AiMessage().setKind("USER_INPUT")
                .setContent(new ObjectMapper().writeValueAsString("{这是一段原文}"));
        assertThat(codec.toView(message).content().data()).containsEntry("text", "{这是一段原文}");
        message.setKind("TOOL_CALL").setContent("{\"name\":\"ask_user\",\"callId\":\"c1\",\"arguments\":\"{}\"}");
        assertThat(codec.toView(message).content().type()).isEqualTo("TOOL_CALL");
        message.setKind("TOOL_RESULT").setContent("{\"name\":\"ask_user\",\"callId\":\"c1\",\"result\":\"null\"}");
        assertThat(codec.toView(message).content().data()).containsEntry("result", "null");
    }

    @Test void readsStoredTextAndWrappedUserQuestionTools() {
        var message = new AiMessage().setKind("ASSISTANT_FINAL")
                .setContent("{\"type\":\"TEXT\",\"data\":{\"text\":\"历史文本\"}}");
        assertThat(codec.toView(message).content().data()).containsEntry("text", "历史文本");
        message.setContent("{\"text\":\"旧文本\"}");
        assertThat(codec.toView(message).content().data()).containsEntry("text", "旧文本");
        message.setKind("TOOL_CALL").setContent("{\"type\":\"TOOL_CALL\",\"data\":{\"name\":\"ask_user\",\"callId\":\"c1\",\"arguments\":\"{}\"}}");
        assertThat(codec.toView(message).content().type()).isEqualTo("TOOL_CALL");
        message.setKind("TOOL_RESULT").setContent("{\"type\":\"TOOL_RESULT\",\"data\":{\"name\":\"ask_user\",\"callId\":\"c1\",\"result\":\"yes\"}}");
        assertThat(codec.toView(message).content().data()).containsEntry("result", "yes");
    }

    @Test void legacyObjectsAndOtherToolsDoNotExposeInternalContent() {
        var message = new AiMessage().setKind("ASSISTANT_FINAL").setContent("{\"type\":\"PLAN\",\"data\":{\"plan\":{}}}");
        assertThat(codec.toView(message).content().data()).containsEntry("text", "该历史消息暂时无法展示。");
        message.setKind("TOOL_RESULT").setContent("{\"name\":\"query_sql_data\",\"result\":\"secret\"}");
        assertThat(codec.toView(message).content().data()).containsEntry("text", "");
    }
}
