package com.qiheng.erp.ai.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.memory.node.PlannerMemoryContext;
import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.qiheng.erp.ai.graph.support.NodeMemoryRecorder;
import com.qiheng.erp.ai.service.VisibleResultFactory;
import com.qiheng.erp.ai.service.progress.RunProgressPublisher;
import com.qiheng.erp.ai.constant.NeedCheckResult;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.qiheng.erp.ai.constant.GraphStateKeys.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlannerNodeMemoryTest {

    private static final String SCHEMA = "# Table: product\n[\n(product_code:VARCHAR)\n]";

    @Test
    void invalidJsonProducesPrivateFeedbackAndRoutesBackToPlanner() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            NodeMemoryRecorder recorder = mock(NodeMemoryRecorder.class);
            NeedCheckNode check = new NeedCheckNode(mapper, validators.getValidator(), 2, 3, 6, recorder,
                    mock(ConversationMessageStore.class), new ConversationThreads(), new VisibleResultFactory());
            Map<String, Object> update = check.apply(new OverAllState(Map.of(
                    USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "repair-1", PLANNER_OUTPUT, "{ broken",
                    NEEDS, List.of(), REPAIR_COUNT, 0)));

            assertEquals(NeedCheckResult.RETRY_FORMAT.name(), update.get(NEED_CHECK_RESULT));
            assertEquals(1, update.get(REPAIR_COUNT));
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> failure = ArgumentCaptor.forClass(Map.class);
            verify(recorder).formatFeedback(eq(1L), eq(2L), eq("repair-1"), failure.capture());
            // 反馈明细必须包含错误消息，planner 只有据此才能修复输出
            assertTrue(failure.getValue().containsKey("message"));
        }
    }

    @Test
    void injectsHistoryAndRecordsInputAndRawOutput() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{ broken")))));
        PlannerMemoryContext context = mock(PlannerMemoryContext.class);
        when(context.read(any(), any())).thenReturn(List.of(new AssistantMessage("上一轮已接受需求")));
        NodeMemoryRecorder recorder = mock(NodeMemoryRecorder.class);
        PlannerNode planner = new PlannerNode(ChatClient.create(model), "测试", Clock.systemUTC(),
                recorder, mapper, context);

        Map<String, Object> update = planner.apply(new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "request-1", USER_REQUEST, "新需求")));

        assertEquals("{ broken", update.get(PLANNER_OUTPUT));
        // 轮次号由 planner 自己累加
        assertEquals(1, update.get(ROUND_NO));
        // 输入记忆记的是"本轮真正喂给模型的输入"，带轮次、时间与任务句
        verify(recorder).plannerInput(eq(1L), eq(2L), eq("request-1"), eq(1), anyString(),
                eq("拆解需求"), contains("新需求"));
        verify(recorder).modelOutput(1L, 2L, "request-1", 1, "{ broken");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertTrue(prompt.getValue().getContents().contains("上一轮已接受需求"));
        assertTrue(prompt.getValue().getContents().contains("新需求"));
    }

    @Test
    void fedUserMessageEqualsRecordedInputText() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{}")))));
        NodeMemoryRecorder recorder = mock(NodeMemoryRecorder.class);
        PlannerNode planner = new PlannerNode(ChatClient.create(model), "测试", Clock.systemUTC(),
                recorder, mapper, null);

        planner.apply(new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "request-1", USER_REQUEST, "查库存")));

        ArgumentCaptor<String> recorded = ArgumentCaptor.forClass(String.class);
        verify(recorder).plannerInput(anyLong(), anyLong(), any(), anyInt(), any(), any(), recorded.capture());
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        // 工作记忆里存的那一行，就是模型实际收到的那条消息
        assertTrue(recorded.getValue().startsWith("[输入] "));
        assertEquals(recorded.getValue(), userText(prompt.getValue()));
    }

    @Test
    void clarificationRoundRecordsLatestAnswerAsInput() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{}")))));
        NodeMemoryRecorder recorder = mock(NodeMemoryRecorder.class);
        PlannerNode planner = new PlannerNode(ChatClient.create(model), "测试", Clock.systemUTC(),
                recorder, mapper, null);

        planner.apply(new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "request-2", USER_REQUEST, "新需求",
                CLARIFICATION_HISTORY, List.of(Map.of("questions", List.of(), "answer", "补充说明库存范围")))));

        // 澄清后重入：本轮输入仍是完整的原始需求 + 澄清历史，任务句不变
        verify(recorder).plannerInput(eq(1L), eq(2L), eq("request-2"), eq(1), anyString(), anyString(),
                contains("补充说明库存范围"));
        verify(recorder).modelOutput(eq(1L), eq(2L), eq("request-2"), eq(1), anyString());
    }

    @Test
    void clarificationIsRenderedAsQuestionAnswerText() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{}")))));
        PlannerNode planner = new PlannerNode(ChatClient.create(model), "测试", Clock.systemUTC(),
                mock(NodeMemoryRecorder.class), mapper, null);

        // 前端提交时已把「问题+答案」拼成文本，后端原样搬进本轮输入
        planner.apply(new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "request-2", USER_REQUEST, "新需求",
                CLARIFICATION_HISTORY, List.of(Map.of(
                        "questions", List.of(Map.of("id", "period", "question", "销量统计哪个自然月？",
                                "options", List.of("本月", "上月"))),
                        "answer", "销量统计哪个自然月？\n上月")))));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        // 只看用户消息：system prompt 里的输出格式说明本身含 questions 字段
        String content = userText(prompt.getValue());
        assertTrue(content.contains("## 已确认的澄清"));
        assertTrue(content.contains("销量统计哪个自然月？"));
        assertTrue(content.contains("上月"));
        // 不再渲染成 JSON：模型不需要解析结构
        assertFalse(content.contains("\"questions\""));
    }

    @Test
    void compactionProgressReachesPublisher() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{}")))));
        PlannerMemoryContext context = mock(PlannerMemoryContext.class);
        // 读历史时压缩器回调：先开始、后结束
        when(context.read(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<Boolean> listener = invocation.getArgument(1);
            listener.accept(true);
            listener.accept(false);
            return List.of();
        });
        RunProgressPublisher publisher = mock(RunProgressPublisher.class);
        PlannerNode planner = new PlannerNode(ChatClient.create(model), "测试", Clock.systemUTC(),
                mock(NodeMemoryRecorder.class), mapper, context, publisher);

        planner.apply(new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "request-1", USER_REQUEST, "查库存")));

        // 压缩期间用户能看到"正在压缩历史记忆"，读完后自动收尾
        verify(publisher).nodeStarted(1L, MEMORY_COMPACT_NODE);
        verify(publisher).nodeFinished(eq(1L), eq(MEMORY_COMPACT_NODE), anyInt());
    }

    @Test
    void noCompactionMeansNoProgressEvent() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{}")))));
        PlannerMemoryContext context = mock(PlannerMemoryContext.class);
        when(context.read(any(), any())).thenReturn(List.of());
        RunProgressPublisher publisher = mock(RunProgressPublisher.class);
        PlannerNode planner = new PlannerNode(ChatClient.create(model), "测试", Clock.systemUTC(),
                mock(NodeMemoryRecorder.class), mapper, context, publisher);

        planner.apply(new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "request-1", USER_REQUEST, "查库存")));

        verifyNoInteractions(publisher);
    }

    @Test
    void everyRoundIsRecordedWithItsOwnRoundNumber() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{}")))));
        NodeMemoryRecorder recorder = mock(NodeMemoryRecorder.class);
        PlannerNode planner = new PlannerNode(ChatClient.create(model), "测试", Clock.systemUTC(),
                recorder, mapper, null);

        Map<String, Object> first = planner.apply(new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "request-2", USER_REQUEST, "新需求",
                ROUND_NO, 1)));
        Map<String, Object> second = planner.apply(new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "request-2", USER_REQUEST, "新需求",
                ROUND_NO, first.get(ROUND_NO))));

        assertEquals(2, first.get(ROUND_NO));
        assertEquals(3, second.get(ROUND_NO));
        // 每轮都记一条：轮次头和任务句每轮都不同，本来就没有重复内容
        verify(recorder, times(2)).plannerInput(eq(1L), eq(2L), eq("request-2"), anyInt(), anyString(),
                anyString(), anyString());
    }

    @Test
    void schemaGoesToSystemPromptAndNotIntoTheRoundInput() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{}")))));
        PlannerNode planner = new PlannerNode(ChatClient.create(model), "静态提示词", Clock.systemUTC(),
                mock(NodeMemoryRecorder.class), mapper, null);

        planner.apply(new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "request-3", USER_REQUEST, "新需求",
                AUTHORIZED_SCHEMA_REF, Map.of("schema", SCHEMA))));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        // 表结构在 system prompt 里：一轮任务内恒定，不该每轮拼进动态上下文
        assertTrue(prompt.getValue().getInstructions().stream()
                .anyMatch(message -> message.getText().contains(SCHEMA)));
        assertFalse(userText(prompt.getValue()).contains(SCHEMA));
    }

    @Test
    void businessKnowledgeGoesToSystemPrompt() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{}")))));
        PlannerNode planner = new PlannerNode(ChatClient.create(model), "静态提示词", Clock.systemUTC(),
                mock(NodeMemoryRecorder.class), mapper, null);

        planner.apply(new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "request-9", USER_REQUEST, "查库存",
                BUSINESS_KNOWLEDGE, "# 仓储业务口径\n库存三个口径……")));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        // 业务口径一轮任务内恒定，放 system prompt，不进每轮的动态上下文
        assertTrue(prompt.getValue().getInstructions().stream()
                .anyMatch(message -> message.getText().contains("# 仓储业务口径")));
        assertFalse(userText(prompt.getValue()).contains("# 仓储业务口径"));
    }

    @Test
    void emptyBusinessKnowledgeIsOmitted() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{}")))));
        PlannerNode planner = new PlannerNode(ChatClient.create(model), "静态提示词", Clock.systemUTC(),
                mock(NodeMemoryRecorder.class), mapper, null);

        planner.apply(new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "request-10", USER_REQUEST, "查库存",
                BUSINESS_KNOWLEDGE, "")));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        // 没有口径时不拼空标题
        assertFalse(prompt.getValue().getInstructions().stream()
                .anyMatch(message -> message.getText().contains("业务口径")));
    }

    /** 取 prompt 里的用户消息正文：getContents 会把 system 消息一起拼进来。 */
    private String userText(Prompt prompt) {
        return prompt.getInstructions().stream()
                .filter(message -> message.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER)
                .map(message -> message.getText() == null ? "" : message.getText())
                .reduce("", (left, right) -> left + right);
    }

    @Test
    void roundInputCarriesRoundHeaderTaskSentenceAndTimeScope() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{}")))));
        PlannerNode planner = new PlannerNode(ChatClient.create(model), "静态提示词",
                Clock.fixed(Instant.parse("2026-10-22T03:15:00Z"), ZoneOffset.UTC),
                mock(NodeMemoryRecorder.class), mapper, null);

        planner.apply(new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "request-4", USER_REQUEST, "查库存")));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String content = prompt.getValue().getContents();
        assertTrue(content.contains("第 1 轮"));
        assertTrue(content.contains("2026-10-22 03:15:00"));
        assertTrue(content.contains("本轮任务：拆解需求"));
        // 时间口径由代码算好给模型，不让模型自己推月份边界；压成一行省 token
        assertTrue(content.contains("本月 2026-10-01~2026-10-31"));
        assertTrue(content.contains("下月 2026-11-01~2026-11-30"));
        // 上限不注入：它是代码约束
        assertFalse(content.contains("上限"));
    }

    @Test
    void emptySectionsAreOmittedEntirely() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{}")))));
        PlannerNode planner = new PlannerNode(ChatClient.create(model), "静态提示词", Clock.systemUTC(),
                mock(NodeMemoryRecorder.class), mapper, null);

        planner.apply(new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "request-5", USER_REQUEST, "查库存")));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String content = prompt.getValue().getContents();
        // 没有澄清、没有已有需求、没有候选方案：这几段整段不出现
        assertFalse(content.contains("## 已确认的澄清"));
        assertFalse(content.contains("## 已有需求"));
        assertFalse(content.contains("## 已确认的候选方案"));
        assertTrue(content.contains("## 原始需求"));
    }

    @Test
    void repairRoundFeedsBackOnlyTheErrorWithoutDuplicatingPreviousOutput() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{}")))));
        PlannerNode planner = new PlannerNode(ChatClient.create(model), "静态提示词", Clock.systemUTC(),
                mock(NodeMemoryRecorder.class), mapper, null);

        planner.apply(new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "request-6", USER_REQUEST, "查库存",
                PLANNER_OUTPUT, "{\"needs\":[]}",
                REPAIR_ERROR, Map.of("type", "NeedValidationException", "message", "needs 不能为空"),
                REPAIR_ISSUES, List.of(Map.of("need_id", "N1", "problem", "漏查在途", "suggestion", "追加")))));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String content = prompt.getValue().getContents();
        assertTrue(content.contains("本轮任务：修正输出"));
        // 上一轮输出只在历史里（模型自己的 [输出]），输入不再重复一份
        assertFalse(content.contains("## 上一轮产出"));
        assertTrue(content.contains("## 具体错误"));
        assertTrue(content.contains("## 审阅反馈"));
        assertTrue(content.contains("漏查在途"));
    }

    @Test
    void repairRoundSeesPreviousOutputExactlyOnceFromHistory() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{}")))));
        // 历史里已经有上一轮的原始输出
        PlannerMemoryContext context = mock(PlannerMemoryContext.class);
        when(context.read(any(), any())).thenReturn(List.of(
                new AssistantMessage("[输出] {\"needs\":[],\"broken\":true}")));
        PlannerNode planner = new PlannerNode(ChatClient.create(model), "静态提示词", Clock.systemUTC(),
                mock(NodeMemoryRecorder.class), mapper, context);

        planner.apply(new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "request-6", USER_REQUEST, "查库存",
                PLANNER_OUTPUT, "{\"needs\":[],\"broken\":true}",
                REPAIR_ERROR, Map.of("type", "NeedValidationException", "message", "needs 不能为空"))));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        // 上一轮输出在整条提示词里只出现一次：历史的那条 [输出]
        assertEquals(1, occurrences(prompt.getValue().getContents(), "{\"needs\":[],\"broken\":true}"));
    }

    /** 统计子串出现次数，用于断言同一段内容没有被重复注入。 */
    private int occurrences(String text, String target) {
        int count = 0;
        int index = text.indexOf(target);
        while (index >= 0) {
            count++;
            index = text.indexOf(target, index + target.length());
        }
        return count;
    }

    @Test
    void businessRepairGetsItsOwnTaskSentence() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage("{}")))));
        PlannerNode planner = new PlannerNode(ChatClient.create(model), "静态提示词", Clock.systemUTC(),
                mock(NodeMemoryRecorder.class), mapper, null);

        planner.apply(new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "request-7", USER_REQUEST, "查库存",
                REPAIR_ERROR, Map.of("type", "NeedReviewIssues", "message", "存在业务遗漏"))));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        // 业务漏查与格式错要模型做的事不一样，任务句不能混
        assertTrue(prompt.getValue().getContents().contains("本轮任务：补齐证据"));
    }


    /** 造一条已有需求：结构与真实需求一致。 */
    private Map<String, Object> needMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", "N1");
        map.put("intent", "DATA");
        map.put("need", "查当前库存");
        map.put("depends", List.of());
        map.put("condition", null);
        map.put("evidence_plan", List.of(Map.of(
                "id", "N1-E1", "label", "当前库存", "instruction", "查库存",
                "source", "SQL", "depends", List.of())));
        map.put("status", "PENDING");
        return map;
    }
}
