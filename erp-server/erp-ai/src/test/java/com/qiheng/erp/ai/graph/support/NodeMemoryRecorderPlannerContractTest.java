package com.qiheng.erp.ai.graph.support;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.constant.NodeCode;
import com.qiheng.erp.ai.domain.entity.AiNodeWorkingMemory;
import com.qiheng.erp.ai.memory.node.NodeMemoryStore;
import com.qiheng.erp.ai.memory.node.PlannerMemoryContext;
import com.qiheng.erp.ai.memory.summary.ContextCompressor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Map;

import static com.qiheng.erp.ai.constant.GraphStateKeys.CONVERSATION_ID;
import static com.qiheng.erp.ai.constant.GraphStateKeys.REQUEST_ID;
import static com.qiheng.erp.ai.constant.GraphStateKeys.USER_ID;
import static com.qiheng.erp.ai.constant.GraphStateKeys.USER_REQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写读契约：{@link NodeMemoryRecorder} 写入的模型视角记忆，
 * 必须能被 {@link PlannerMemoryContext} 原样读回为 Planner 的历史消息。
 *
 * <p>工作记忆的正文就是模型实际收到与返回的内容，一字不差；两端任何一侧
 * 更改标记或内容形态，本测试都会失败提醒同步修改。</p>
 *
 * @author Li
 * @since 2026-10-17
 */
class NodeMemoryRecorderPlannerContractTest {

    /** 模型原始输出：需求数组协议。 */
    private static final String PLAN_OUTPUT = """
            {"needs":[{"id":"N1","intent":"DATA","need":"统计上月销量","depends":[],"condition":null,
             "evidence_plan":[{"id":"N1-E1","label":"上月销量","instruction":"查询销量","source":"SQL","depends":[]}],
             }],
             "needs_clarification":false,"questions":[]}
            """;

    @Test
    void recorderEntriesAreReadBackAsPlannerHistory() throws Exception {
        NodeMemoryStore store = mock(NodeMemoryStore.class);
        ObjectMapper mapper = new ObjectMapper();
        NodeMemoryRecorder recorder = new NodeMemoryRecorder(store, mapper);
        // 节点边界内的真实写入顺序：Planner 入口写本轮输入与模型输出，计划生效后只追“通过”标记
        recorder.plannerInput(1L, 2L, "old-request", 1, "2026-10-22 03:15:00 +0800", "拆解需求",
                "[输入] 统计上月销量");
        recorder.modelOutput(1L, 2L, "old-request", 1, PLAN_OUTPUT);
        recorder.acceptedPlan(1L, 2L, "old-request", "PLAN_READY");

        // 写入端契约：模型视角的两类两张表都写，过程事件只写流水
        ArgumentCaptor<String> events = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> transcripts = ArgumentCaptor.forClass(String.class);
        verify(store, times(2)).appendTranscript(eq(1L), eq(2L), eq(NodeCode.PLANNER),
                events.capture(), transcripts.capture());
        assertEquals("USER_INPUT", mapper.readTree(events.getAllValues().getFirst()).path("eventType").asText());
        assertEquals("MODEL_OUTPUT", mapper.readTree(events.getAllValues().get(1)).path("eventType").asText());
        assertEquals("[输入] 统计上月销量", transcripts.getAllValues().getFirst());
        assertEquals("[输出] " + PLAN_OUTPUT, transcripts.getAllValues().get(1));
        verify(store).appendEvent(eq(1L), eq(2L), eq(NodeCode.PLANNER), contains("ACCEPTED_PLAN"));

        // 读取端契约：工作记忆行原样变历史消息，模型看到的与自己上轮说的一字不差
        when(store.read(1L, 2L, NodeCode.PLANNER)).thenReturn(List.of(
                row(1L, "[输入] 统计上月销量"),
                row(2L, "[输出] " + PLAN_OUTPUT)));
        PlannerMemoryContext context = new PlannerMemoryContext(store,
                new ContextCompressor(mock(ChatModel.class), 500000, 20), mapper);

        List<Message> history = context.read(new OverAllState(Map.of(
                CONVERSATION_ID, 1L, USER_ID, 2L, REQUEST_ID, "now", USER_REQUEST, "统计本月销量")));

        assertEquals(2, history.size());
        assertInstanceOf(UserMessage.class, history.getFirst());
        assertTrue(history.getFirst().getText().contains("统计上月销量"));
        assertInstanceOf(AssistantMessage.class, history.getLast());
        assertTrue(history.getLast().getText().contains("查询销量"));
    }

    /** 按真实行形态重建工作记忆条目，仅替换主键与排序字段。 */
    private AiNodeWorkingMemory row(long id, String content) {
        return new AiNodeWorkingMemory().setId(id).setConversationId(1L).setUserId(2L)
                .setNodeCode(NodeCode.PLANNER)
                .setSortOrder(id).setContent(content).setSourceMemoryIds("[" + id + "]");
    }
}
