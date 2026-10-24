package com.qiheng.erp.ai.graph.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.constant.NodeCode;
import com.qiheng.erp.ai.domain.dto.needs.ClarificationQuestion;
import com.qiheng.erp.ai.memory.node.NodeMemoryStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NodeMemoryRecorderTest {

    private final NodeMemoryStore store = mock(NodeMemoryStore.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final NodeMemoryRecorder recorder = new NodeMemoryRecorder(store, mapper);

    @Test
    void plannerInputWritesEventAndMarkedTranscript() {
        recorder.plannerInput(10L, 1L, "request-1", 1, "2026-10-22 03:15:00 +0800", "拆解需求", "[输入] 统计销量");

        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> transcript = ArgumentCaptor.forClass(String.class);
        verify(store).appendTranscript(eq(10L), eq(1L), eq(NodeCode.PLANNER), event.capture(), transcript.capture());
        assertTrue(event.getValue().contains("USER_INPUT"));
        // 工作记忆行是带标记的纯文本，一行 = 模型的一条消息
        assertEquals("[输入] 统计销量", transcript.getValue());
    }

    @Test
    void plannerInputMarksUnmarkedText() {
        recorder.plannerInput(10L, 1L, "request-1", 1, "2026-10-22 03:15:00 +0800", "拆解需求", "统计销量");

        ArgumentCaptor<String> transcript = ArgumentCaptor.forClass(String.class);
        verify(store).appendTranscript(anyLong(), anyLong(), anyString(), anyString(), transcript.capture());
        assertEquals("[输入] 统计销量", transcript.getValue());
    }

    @Test
    void modelOutputWritesEventAndMarkedTranscript() {
        recorder.modelOutput(10L, 1L, "request-1", 1, "{ broken");

        ArgumentCaptor<String> event = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> transcript = ArgumentCaptor.forClass(String.class);
        verify(store).appendTranscript(eq(10L), eq(1L), eq(NodeCode.PLANNER), event.capture(), transcript.capture());
        assertTrue(event.getValue().contains("MODEL_OUTPUT"));
        assertEquals("[输出] { broken", transcript.getValue());
    }

    @Test
    void acceptedPlanIsSlimMarkerWithoutPlanBody() throws Exception {
        recorder.acceptedPlan(10L, 1L, "request-1", "PLAN_READY");

        // 定稿只追“通过”标记，需求正文由同轮模型输出原文承载
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(store).appendEvent(eq(10L), eq(1L), eq(NodeCode.PLANNER), content.capture());
        JsonNode body = mapper.readTree(content.getValue());
        assertEquals("ACCEPTED_PLAN", body.path("eventType").asText());
        assertEquals("request-1", body.path("requestId").asText());
        assertEquals("PLAN_READY", body.path("status").asText());
        assertFalse(body.has("plan"));
    }

    @Test
    void clarificationAskedCarriesQuestionsAndPauseId() throws Exception {
        recorder.clarificationAsked(10L, 1L, "request-1", "pause-1",
                List.of(new ClarificationQuestion("period", "需要分析哪个月份？", List.of("本月", "上月"))));

        // 问题原文只在这里保存一份：模型视角里它在模型自己的输出中，不进工作记忆
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(store).appendEvent(eq(10L), eq(1L), eq(NodeCode.PLANNER), content.capture());
        JsonNode body = mapper.readTree(content.getValue());
        assertEquals("CLARIFICATION_ASKED", body.path("eventType").asText());
        assertEquals("request-1", body.path("requestId").asText());
        assertEquals("pause-1", body.path("pauseId").asText());
        assertEquals("需要分析哪个月份？", body.path("questions").get(0).path("question").asText());
    }

    @Test
    void clarificationAnsweredCarriesAnswerAndPauseId() throws Exception {
        recorder.clarificationAnswered(10L, 1L, "request-2", "pause-1", "2026年9月");

        // 回答条目只进流水：模型视角里它在下一轮输入的澄清段中
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(store).appendEvent(eq(10L), eq(1L), eq(NodeCode.PLANNER), content.capture());
        JsonNode body = mapper.readTree(content.getValue());
        assertEquals("CLARIFICATION_ANSWERED", body.path("eventType").asText());
        assertEquals("request-2", body.path("requestId").asText());
        assertEquals("pause-1", body.path("pauseId").asText());
        assertEquals("2026年9月", body.path("answer").asText());
    }

    @Test
    void formatExhaustedCarriesEndingMarker() throws Exception {
        recorder.formatExhausted(10L, 1L, "request-3");

        // 耗尽结局标记：本轮没有产物收场
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(store).appendEvent(eq(10L), eq(1L), eq(NodeCode.PLANNER), content.capture());
        JsonNode body = mapper.readTree(content.getValue());
        assertEquals("FORMAT_RETRY_EXHAUSTED", body.path("eventType").asText());
        assertEquals("request-3", body.path("requestId").asText());
        assertEquals("EXHAUSTED", body.path("result").asText());
    }

    @Test
    void formatFeedbackIsSerializedAndAppended() {
        recorder.formatFeedback(10L, 1L, "request-1", Map.of("message", "字段缺失"));

        verify(store).appendEvent(eq(10L), eq(1L), eq(NodeCode.PLANNER), contains("FORMAT_FEEDBACK"));
        verify(store).appendEvent(anyLong(), anyLong(), anyString(), contains("字段缺失"));
    }

    @Test
    void eventOnlyEntriesNeverTouchWorkingMemory() {
        // 工作记忆只装 planner 的输入与输出。need_check / need_review / 恢复入口写的是
        // 过程事件，它们已经通过 State 进入下一轮输入，再写进工作记忆就会重复注入。
        recorder.clarificationAsked(10L, 1L, "request-1", "pause-1",
                List.of(new ClarificationQuestion("period", "哪个月份？", List.of())));
        recorder.clarificationAnswered(10L, 1L, "request-2", "pause-1", "上月");
        recorder.acceptedPlan(10L, 1L, "request-1", "PLAN_READY");
        recorder.acceptedNeeds(10L, 1L, "request-1", "NEEDS_READY");
        recorder.reviewIssues(10L, 1L, "request-1", List.of());
        recorder.formatExhausted(10L, 1L, "request-1");
        recorder.formatFeedback(10L, 1L, "request-1", Map.of("message", "字段缺失"));

        verify(store, times(7)).appendEvent(anyLong(), anyLong(), anyString(), anyString());
        verify(store, never()).appendTranscript(anyLong(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void serializationFailureIsSwallowed() {
        ObjectMapper failing = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("序列化失败") { };
            }
        };

        assertDoesNotThrow(() -> new NodeMemoryRecorder(store, failing)
                .plannerInput(10L, 1L, "request-1", 1, "2026-10-22 03:15:00 +0800", "拆解需求", "统计销量"));
        verifyNoInteractions(store);
    }

    @Test
    void persistenceFailureIsSwallowed() {
        doThrow(new IllegalStateException("库暂时不可用"))
                .when(store).appendTranscript(anyLong(), anyLong(), anyString(), anyString(), anyString());

        assertDoesNotThrow(() -> recorder.plannerInput(10L, 1L, "request-1", 1, "2026-10-22 03:15:00 +0800",
                "拆解需求", "统计销量"));
    }
}
