package com.qiheng.erp.ai.report.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportConversationReaderTest {
    private final AiMessageMapper messages = mock(AiMessageMapper.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReportConversationReader reader = new ReportConversationReader(messages, mapper);

    @Test
    void readsPairedQuestionsAndAnswersWithoutInternalIds() throws Exception {
        String arguments = mapper.writeValueAsString(Map.of("questions", List.of(
                Map.of("id", "q1", "question", "范围？"),
                Map.of("id", "q2", "question", "其他要求？"))));
        String result = mapper.writeValueAsString(Map.of("answers", List.of(
                Map.of("questionId", "q1", "selectedOptions", List.of("仓库一", "仓库二"), "customText", ""),
                Map.of("questionId", "q2", "selectedOptions", List.of(), "customText", "查看金额")),
                "note", "十月"));
        when(messages.selectRunMessages(1, 2, "run")) .thenReturn(List.of(
                new AiMessage().setKind("USER_INPUT").setContent(mapper.writeValueAsString("查看库存")),
                new AiMessage().setKind("TOOL_CALL").setContent(mapper.writeValueAsString(Map.of(
                        "name", "ask_user", "callId", "c1", "arguments", arguments))),
                new AiMessage().setKind("TOOL_RESULT").setContent(mapper.writeValueAsString(Map.of(
                        "name", "ask_user", "callId", "c1", "result", result)))));
        String text = reader.read(new AgentToolContext(1, 2), "run");
        assertThat(text).contains("查看库存", "问题：范围？", "回答：仓库一、仓库二", "回答：查看金额", "其他补充：十月")
                .doesNotContain("q1", "c1");
    }

    @Test
    void rejectsOrphanedAnswer() throws Exception {
        when(messages.selectRunMessages(1, 2, "run")).thenReturn(List.of(
                new AiMessage().setKind("USER_INPUT").setContent("\"需求\""),
                new AiMessage().setKind("TOOL_RESULT").setContent("{\"name\":\"ask_user\",\"callId\":\"missing\"}")));
        assertThatThrownBy(() -> reader.read(new AgentToolContext(1, 2), "run"))
                .hasMessageContaining("缺少对应问题");
    }
}
