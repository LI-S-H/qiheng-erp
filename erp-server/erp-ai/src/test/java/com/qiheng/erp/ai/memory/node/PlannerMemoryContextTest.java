package com.qiheng.erp.ai.memory.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.constant.NodeCode;
import com.qiheng.erp.ai.domain.entity.AiNodeWorkingMemory;
import com.qiheng.erp.ai.memory.summary.ContextCompressor;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.qiheng.erp.ai.constant.GraphStateKeys.CONVERSATION_ID;
import static com.qiheng.erp.ai.constant.GraphStateKeys.REQUEST_ID;
import static com.qiheng.erp.ai.constant.GraphStateKeys.USER_ID;
import static com.qiheng.erp.ai.constant.GraphStateKeys.USER_REQUEST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PlannerMemoryContextTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private final NodeMemoryStore store = mock(NodeMemoryStore.class);

    @Test
    void transcriptRowsBecomeHistoryMessagesInOrder() throws Exception {
        when(store.read(1L, 2L, NodeCode.PLANNER)).thenReturn(List.of(
                row(1, "[输入] 第 1 轮输入"),
                row(2, "[输出] 第 1 轮输出")));
        PlannerMemoryContext context = new PlannerMemoryContext(store, noCompressor(), mapper);

        List<Message> history = context.read(state());

        assertEquals(2, history.size());
        assertInstanceOf(UserMessage.class, history.getFirst());
        assertInstanceOf(AssistantMessage.class, history.getLast());
        // 正文原样带出去：模型看到的就是工作记忆里存的那一行
        assertEquals("[输入] 第 1 轮输入", history.getFirst().getText());
        assertEquals("[输出] 第 1 轮输出", history.getLast().getText());
    }

    @Test
    void clarificationRoundKeepsQuestionAndAnswerAdjacent() throws Exception {
        // 上一轮：模型问了问题；下一轮：用户回答进了本轮输入的澄清段
        when(store.read(1L, 2L, NodeCode.PLANNER)).thenReturn(List.of(
                row(1, "[输入] 第 1 轮输入\n## 原始需求\n统计本月销量"),
                row(2, "[输出] {\"needs\":[],\"needs_clarification\":true,"
                        + "\"questions\":[{\"id\":\"period\",\"question\":\"销量统计哪个自然月？\"}]}"),
                row(3, "[输入] 第 2 轮输入\n## 已确认的澄清\n销量统计哪个自然月？\n上月")));
        PlannerMemoryContext context = new PlannerMemoryContext(store, noCompressor(), mapper);

        List<Message> history = context.read(state());

        // 问题与答案在历史里天然相邻：模型看得见自己上轮问了什么、用户答了什么
        assertEquals(3, history.size());
        assertTrue(history.get(1).getText().contains("销量统计哪个自然月"));
        assertTrue(history.get(2).getText().contains("上月"));
    }

    @Test
    void failedOutputAlsoEntersHistory() throws Exception {
        when(store.read(1L, 2L, NodeCode.PLANNER)).thenReturn(List.of(
                row(1, "[输入] 第 1 轮输入"),
                row(2, "[输出] { broken"),
                row(3, "[输入] 第 2 轮输入\n## 具体错误\nneeds 不能为空")));
        PlannerMemoryContext context = new PlannerMemoryContext(store, noCompressor(), mapper);

        List<Message> history = context.read(state());

        // 忠实记录模型看到什么：坏输出也在历史里，紧跟着是带错误反馈的下一轮输入
        assertEquals(3, history.size());
        assertTrue(history.get(1).getText().contains("{ broken"));
        assertTrue(history.get(2).getText().contains("needs 不能为空"));
    }

    @Test
    void legacyEventRowsAreSkippedWithoutBreakingOthers() throws Exception {
        when(store.read(1L, 2L, NodeCode.PLANNER)).thenReturn(List.of(
                row(1, "{\"eventType\":\"USER_INPUT\",\"requestId\":\"old\",\"query\":\"旧需求\"}"),
                row(2, "[输入] 第 1 轮输入"),
                row(3, "[输出] 第 1 轮输出")));
        PlannerMemoryContext context = new PlannerMemoryContext(store, noCompressor(), mapper);

        List<Message> history = context.read(state());

        // 旧的事件 JSON 行不在新协议里，跳过即可，不影响其它行
        assertEquals(2, history.size());
        assertEquals("[输入] 第 1 轮输入", history.getFirst().getText());
    }

    @Test
    void compressionPersistsPlainTextRowsWithSourceReferences() throws Exception {
        List<AiNodeWorkingMemory> rows = transcript(3);
        when(store.read(1L, 2L, NodeCode.PLANNER)).thenReturn(rows);
        ChatModel summary = mock(ChatModel.class);
        when(summary.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("已确认库存范围"))))));
        PlannerMemoryContext context = new PlannerMemoryContext(store,
                new ContextCompressor(summary, 10, 2), mapper);

        List<Message> result = context.read(state());

        // 触发阈值 10、最近窗口预算 5：只能留下最后一条，其余压进摘要
        assertEquals(2, result.size());
        assertTrue(result.getFirst().getText().contains("已确认库存范围"));
        // 摘要带摘要标记，模型认得出这是压缩出来的历史
        assertTrue(result.getFirst().getText().startsWith(WorkingMemoryTranscript.SUMMARY));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AiNodeWorkingMemory>> captured = ArgumentCaptor.forClass(List.class);
        verify(store).replaceWorkingMemory(eq(1L), eq(2L), eq(NodeCode.PLANNER), same(rows), captured.capture());
        List<AiNodeWorkingMemory> saved = captured.getValue();
        // 回写的是纯文本行，不是事件 JSON；保留的最近一条仍是模型自己的输出
        assertEquals(2, saved.size());
        assertTrue(saved.getFirst().getContent().startsWith(WorkingMemoryTranscript.SUMMARY));
        assertTrue(saved.get(1).getContent().startsWith(WorkingMemoryTranscript.OUTPUT));
        // 被摘要掉的原文汇总进摘要行，保留消息各自指向自己的流水行
        assertEquals(List.of(1, 2, 3, 4, 5), mapper.readValue(saved.getFirst().getSourceMemoryIds(), List.class));
        assertEquals("[6]", saved.get(1).getSourceMemoryIds());
    }

    @Test
    void summaryFailureDoesNotReplaceWorkingMemory() throws Exception {
        List<AiNodeWorkingMemory> rows = transcript(3);
        when(store.read(1L, 2L, NodeCode.PLANNER)).thenReturn(rows);
        ChatModel summary = mock(ChatModel.class);
        when(summary.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("摘要失败")));
        PlannerMemoryContext context = new PlannerMemoryContext(store,
                new ContextCompressor(summary, 10, 2), mapper);

        assertEquals(6, context.read(state()).size());
        verify(store, never()).replaceWorkingMemory(anyLong(), anyLong(), anyString(), anyList(), anyList());
    }

    @Test
    void persistenceFailureUsesOriginalContextForThisCall() throws Exception {
        List<AiNodeWorkingMemory> rows = transcript(3);
        when(store.read(1L, 2L, NodeCode.PLANNER)).thenReturn(rows);
        ChatModel summary = mock(ChatModel.class);
        when(summary.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("摘要"))))));
        doThrow(new IllegalStateException("写入失败")).when(store)
                .replaceWorkingMemory(anyLong(), anyLong(), anyString(), anyList(), anyList());
        PlannerMemoryContext context = new PlannerMemoryContext(store,
                new ContextCompressor(summary, 10, 2), mapper);

        assertEquals(6, context.read(state()).size());
    }

    @Test
    void reportsCompactionStartAndFinishAroundCompaction() throws Exception {
        List<AiNodeWorkingMemory> rows = transcript(3);
        when(store.read(1L, 2L, NodeCode.PLANNER)).thenReturn(rows);
        ChatModel summary = mock(ChatModel.class);
        when(summary.stream(any(Prompt.class))).thenReturn(Flux.just(
                new ChatResponse(List.of(new Generation(new AssistantMessage("已确认库存范围"))))));
        PlannerMemoryContext context = new PlannerMemoryContext(store,
                new ContextCompressor(summary, 10, 2), mapper);
        List<Boolean> signals = new ArrayList<>();

        context.read(state(), signals::add);

        // 先 true 后 false：界面在摘要调用期间显示"正在压缩"，结束即收尾
        assertEquals(List.of(true, false), signals);
    }

    @Test
    void reportsCompactionFinishEvenWhenSummaryFails() throws Exception {
        List<AiNodeWorkingMemory> rows = transcript(3);
        when(store.read(1L, 2L, NodeCode.PLANNER)).thenReturn(rows);
        ChatModel summary = mock(ChatModel.class);
        when(summary.stream(any(Prompt.class))).thenReturn(Flux.error(new IllegalStateException("摘要失败")));
        PlannerMemoryContext context = new PlannerMemoryContext(store,
                new ContextCompressor(summary, 10, 2), mapper);
        List<Boolean> signals = new ArrayList<>();

        assertEquals(6, context.read(state(), signals::add).size());

        // 失败也要收尾，否则界面永远停在"正在压缩"
        assertEquals(List.of(true, false), signals);
    }

    @Test
    void doesNotReportWhenCompactionIsNotNeeded() throws Exception {
        when(store.read(1L, 2L, NodeCode.PLANNER)).thenReturn(transcript(1));
        PlannerMemoryContext context = new PlannerMemoryContext(store, noCompressor(), mapper);
        List<Boolean> signals = new ArrayList<>();

        context.read(state(), signals::add);

        assertTrue(signals.isEmpty());
    }

    @Test
    void storeFailureFallsBackToEmptyHistory() throws Exception {
        when(store.read(1L, 2L, NodeCode.PLANNER)).thenThrow(new IllegalStateException("库不可用"));
        PlannerMemoryContext context = new PlannerMemoryContext(store, noCompressor(), mapper);

        // 记忆不可用不影响本轮规划：降级成空历史
        assertEquals(List.of(), context.read(state()));
    }

    @Test
    void noCompressionKeepsWindowUntouched() throws Exception {
        List<AiNodeWorkingMemory> rows = transcript(1);
        when(store.read(1L, 2L, NodeCode.PLANNER)).thenReturn(rows);
        ChatModel summary = mock(ChatModel.class);
        PlannerMemoryContext context = new PlannerMemoryContext(store,
                new ContextCompressor(summary, 500000, 20), mapper);

        assertEquals(2, context.read(state()).size());
        verifyNoInteractions(summary);
        verify(store, never()).replaceWorkingMemory(anyLong(), anyLong(), anyString(), anyList(), anyList());
    }

    /** 三轮对话的转写行：输入与输出各一行。 */
    private List<AiNodeWorkingMemory> transcript(int rounds) {
        List<AiNodeWorkingMemory> rows = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            rows.add(row(i * 2 + 1, "[输入] 第 " + (i + 1) + " 轮输入"));
            rows.add(row(i * 2 + 2, "[输出] 第 " + (i + 1) + " 轮输出"));
        }
        return rows;
    }

    private AiNodeWorkingMemory row(long id, String content) {
        return new AiNodeWorkingMemory().setId(id).setConversationId(1L).setUserId(2L)
                .setNodeCode(NodeCode.PLANNER).setSortOrder(id)
                .setContent(content).setSourceMemoryIds("[" + id + "]");
    }

    private ContextCompressor noCompressor() {
        return new ContextCompressor(mock(ChatModel.class), 500000, 20);
    }

    private OverAllState state() {
        return new OverAllState(Map.of(CONVERSATION_ID, 1L, USER_ID, 2L,
                REQUEST_ID, "now", USER_REQUEST, "当前需求"));
    }
}
