package com.qiheng.erp.ai.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.constant.NeedReviewResult;
import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.qiheng.erp.ai.graph.support.NeedStore;
import com.qiheng.erp.ai.graph.support.NodeMemoryRecorder;
import com.qiheng.erp.ai.service.VisibleResultFactory;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.qiheng.erp.ai.constant.GraphStateKeys.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 业务完整性审阅节点的契约测试。
 *
 * <p>审阅节点只做三件事：把待审需求与表结构喂给模型、把审阅结论分流、
 * 在通过时发布需求数组。模型输出格式不合规时回本节点重问，不回 planner。</p>
 */
class NeedReviewNodeTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NodeMemoryRecorder recorder = mock(NodeMemoryRecorder.class);
    private final ConversationMessageStore messageStore = mock(ConversationMessageStore.class);

    private static final String REVIEW_OK = "{\"verdict\": \"OK\", \"issues\": []}";

    private static final String REVIEW_ISSUES = """
            {"verdict": "ISSUES", "issues": [
              {"need_id": "N1", "problem": "没有查在途采购量", "suggestion": "追加证据项 N1-E6"}
            ]}
            """;

    private NeedReviewNode node(String... outputs) {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            ChatResponse response = mock(ChatResponse.class);
            when(response.getResult()).thenReturn(new Generation(new AssistantMessage(outputs[0])));
            return response;
        });
        return new NeedReviewNode(ChatClient.create(model), "审阅提示词", mapper, 2, 3, recorder,
                messageStore, new ConversationThreads(), new VisibleResultFactory(), Clock.systemDefaultZone());
    }

    private OverAllState stateOf(String reviewOutput) {
        return new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                USER_REQUEST, "查库存",
                PLANNER_OUTPUT, reviewOutput,
                NEEDS, List.of(needMap()),
                AUTHORIZED_SCHEMA_REF, Map.of("schema", "# Table: product"),
                REPAIR_COUNT, 0));
    }

    private Map<String, Object> needMap() {
        Map<String, Object> map = new LinkedHashMap<>();
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

    private Map<String, Object> adviceNeedMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", "N2");
        map.put("intent", "ADVICE");
        map.put("need", "给出补货方案");
        map.put("depends", List.of("N1"));
        map.put("condition", null);
        map.put("evidence_plan", List.of(Map.of(
                "id", "N2-E1", "label", "补货方案", "instruction", "优先调拨再采购",
                "source", "PROPOSAL", "depends", List.of("N1-E1"))));
        map.put("status", "PENDING");
        return map;
    }

    @Test
    void derivedNeedsFromProposalSkipReview() {
        // D 段需求由方案子图自审：本轮草案来自 PROPOSAL_OUTPUT 时，主循环只做结构校验
        ChatModel model = mock(ChatModel.class);
        NeedReviewNode node = new NeedReviewNode(ChatClient.create(model), "审阅提示词", mapper, 2, 3, recorder,
                messageStore, new ConversationThreads(), new VisibleResultFactory(), Clock.systemDefaultZone());
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                USER_REQUEST, "怎么补货",
                PLANNER_OUTPUT, "",
                PROPOSAL_OUTPUT, "{\"needs\":[]}",
                NEEDS, List.of(needMap()),
                AUTHORIZED_SCHEMA_REF, Map.of("schema", "# Table: product"),
                REPAIR_COUNT, 0));

        Map<String, Object> update = node.apply(state);

        assertThat(update.get(NEED_REVIEW_RESULT)).isEqualTo(NeedReviewResult.OK.name());
        assertThat(update.get(PROPOSAL_OUTPUT)).isEqualTo("");
        verify(model, org.mockito.Mockito.never()).call(any(Prompt.class));
    }

    @Test
    void adviceOnlyNeedsSkipTheModelAndGetAccepted() {
        // ADVICE 的 PROPOSAL 项只是交接说明，方案质量由方案子图自审
        ChatModel model = mock(ChatModel.class);
        NeedReviewNode node = new NeedReviewNode(ChatClient.create(model), "审阅提示词", mapper, 2, 3, recorder,
                messageStore, new ConversationThreads(), new VisibleResultFactory(), Clock.systemDefaultZone());
        Map<String, Object> data = needMap();
        data.put("status", "DONE");
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                USER_REQUEST, "怎么补货",
                PLANNER_OUTPUT, "planner-draft",
                NEEDS, List.of(data, adviceNeedMap()),
                AUTHORIZED_SCHEMA_REF, Map.of("schema", "# Table: product"),
                REPAIR_COUNT, 0));

        Map<String, Object> update = node.apply(state);

        assertThat(update.get(NEED_REVIEW_RESULT)).isEqualTo(NeedReviewResult.OK.name());
        // 直接定稿发布，草案一并消费
        assertThat(update.get(PLANNER_OUTPUT)).isEqualTo("");
        assertThat(update).containsKey(PUBLISHED_ARTIFACT);
        verify(model, org.mockito.Mockito.never()).call(any(Prompt.class));
    }

    @Test
    void reviewInputExcludesAdviceNeeds() {
        // 只有 DATA 进审阅：审阅用数据能不能取到这把尺子量不了方案
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(REVIEW_OK)))));
        NeedReviewNode node = new NeedReviewNode(ChatClient.create(model), "审阅提示词", mapper, 2, 3, recorder,
                messageStore, new ConversationThreads(), new VisibleResultFactory(), Clock.systemDefaultZone());
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                USER_REQUEST, "怎么补货",
                NEEDS, List.of(needMap(), adviceNeedMap()),
                AUTHORIZED_SCHEMA_REF, Map.of("schema", "# Table: product"),
                REPAIR_COUNT, 0));

        node.apply(state);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String content = prompt.getValue().getContents();
        // 待审数组里只有 DATA；ADVICE 作为上下文出现，避免 review 每轮都要求补 ADVICE
        String pendingSection = content.substring(content.indexOf("待审需求数组"),
                content.indexOf("同一轮已拆出的 ADVICE"));
        assertThat(pendingSection).contains("N1-E1").doesNotContain("N2-E1");
        assertThat(content).contains("同一轮已拆出的 ADVICE").contains("给出补货方案");
    }

    @Test
    void okVerdictClearsTheConsumedDraft() {
        // 审阅通过即消费完成：本轮草案清空
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                USER_REQUEST, "查库存",
                PLANNER_OUTPUT, "planner-draft",
                NEEDS, List.of(needMap()),
                AUTHORIZED_SCHEMA_REF, Map.of("schema", "# Table: product"),
                REPAIR_COUNT, 0));

        Map<String, Object> update = node(REVIEW_OK).apply(state);

        assertThat(update.get(PLANNER_OUTPUT)).isEqualTo("");
    }

    @Test
    void issuesKeepBothDrafts() {
        // 审阅不通过时草案要留着：它就是"回谁"的信号
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                USER_REQUEST, "查库存",
                PLANNER_OUTPUT, "planner-draft",
                NEEDS, List.of(needMap()),
                AUTHORIZED_SCHEMA_REF, Map.of("schema", "# Table: product"),
                REPAIR_COUNT, 0));

        Map<String, Object> update = node(REVIEW_ISSUES).apply(state);

        assertThat(update).doesNotContainKey(PLANNER_OUTPUT).doesNotContainKey(PROPOSAL_OUTPUT);
    }

    @Test
    void reviewInputCarriesTimeScope() {
        // 不知道"今天"，判不了时间口径算得对不对
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(REVIEW_OK)))));
        NeedReviewNode node = new NeedReviewNode(ChatClient.create(model), "审阅提示词", mapper, 2, 3, recorder,
                messageStore, new ConversationThreads(), new VisibleResultFactory(), Clock.systemDefaultZone());

        node.apply(stateOf(REVIEW_OK));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getContents())
                .contains("时间口径：")
                .contains("相对日期按此推算");
    }

    @Test
    void reviewInputCarriesPreviousIssues() {
        // 上一轮自己提的问题要带上：避免重复报、避免两轮之间来回改口
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(REVIEW_OK)))));
        NeedReviewNode node = new NeedReviewNode(ChatClient.create(model), "审阅提示词", mapper, 2, 3, recorder,
                messageStore, new ConversationThreads(), new VisibleResultFactory(), Clock.systemDefaultZone());
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                USER_REQUEST, "查库存",
                NEEDS, List.of(needMap()),
                AUTHORIZED_SCHEMA_REF, Map.of("schema", "# Table: product"),
                REPAIR_ISSUES, List.of(Map.of("need_id", "N1", "problem", "上一轮的问题", "suggestion", "上一轮的建议")),
                REPAIR_COUNT, 0));

        node.apply(state);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getContents())
                .contains("上一轮你提的问题")
                .contains("上一轮的问题");
    }

    @Test
    void reviewSystemPromptCarriesBusinessKnowledge() {
        // 业务知识是审"业务上站不站得住"的判据基础
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(REVIEW_OK)))));
        NeedReviewNode node = new NeedReviewNode(ChatClient.create(model), "审阅提示词", mapper, 2, 3, recorder,
                messageStore, new ConversationThreads(), new VisibleResultFactory(), Clock.systemDefaultZone());
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                USER_REQUEST, "查库存",
                NEEDS, List.of(needMap()),
                BUSINESS_KNOWLEDGE, "可用库存 = 账面库存 - 锁定库存",
                AUTHORIZED_SCHEMA_REF, Map.of("schema", "# Table: product"),
                REPAIR_COUNT, 0));

        node.apply(state);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String system = prompt.getValue().getInstructions().stream()
                .filter(message -> message.getMessageType() == MessageType.SYSTEM)
                .map(message -> message.getText() == null ? "" : message.getText())
                .reduce("", String::concat);
        assertThat(system).contains("## 业务知识").contains("可用库存 = 账面库存 - 锁定库存");
    }

    @Test
    void reviewInputCarriesTerminalNeedsAsSummary() {
        // 新需求可能引用已完成需求的证据项：只给摘要，不给 instruction
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(REVIEW_OK)))));
        NeedReviewNode node = new NeedReviewNode(ChatClient.create(model), "审阅提示词", mapper, 2, 3, recorder,
                messageStore, new ConversationThreads(), new VisibleResultFactory(), Clock.systemDefaultZone());
        Map<String, Object> done = needMap();
        done.put("id", "N9");
        done.put("status", "DONE");
        done.put("evidence_plan", List.of(Map.of(
                "id", "N9-E1", "label", "净缺口", "instruction", "这段长文本不应该出现在摘要里",
                "source", "PYTHON", "depends", List.of())));
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                USER_REQUEST, "查库存",
                NEEDS, List.of(needMap(), done),
                AUTHORIZED_SCHEMA_REF, Map.of("schema", "# Table: product"),
                REPAIR_COUNT, 0));

        node.apply(state);

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getContents())
                .contains("已完成需求")
                .contains("N9-E1")
                .contains("净缺口")
                .doesNotContain("这段长文本不应该出现在摘要里");
    }

    @Test
    void okVerdictPassesThroughToSelectorAndResetsBudget() {
        Map<String, Object> update = node(REVIEW_OK).apply(stateOf(REVIEW_OK));

        assertThat(update.get(NEED_REVIEW_RESULT)).isEqualTo(NeedReviewResult.OK.name());
        assertThat(update.get(REPAIR_COUNT)).isEqualTo(0);
        assertThat(update.get(REPAIR_ERROR)).isEqualTo(Map.of());
    }

    @Test
    void issuesGoBackToPlannerWithOriginalIssuesAsFeedback() {
        Map<String, Object> update = node(REVIEW_ISSUES).apply(stateOf(REVIEW_ISSUES));

        assertThat(update.get(NEED_REVIEW_RESULT)).isEqualTo(NeedReviewResult.ISSUES.name());
        // 业务漏查走自己的回流计数，不占格式修复的额度
        assertThat(update.get(REVIEW_COUNT)).isEqualTo(1);
        assertThat(update).doesNotContainKey(REPAIR_COUNT);
        assertThat(update.get(REPAIR_ERROR).toString()).contains("业务遗漏");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> issues = (List<Map<String, Object>>) update.get(REPAIR_ISSUES);
        assertThat(issues).hasSize(1);
        assertThat(issues.getFirst()).containsEntry("need_id", "N1");
        assertThat(issues.getFirst()).containsEntry("suggestion", "追加证据项 N1-E6");
        verify(recorder).reviewIssues(eq(1L), eq(2L), eq("req-1"), any());
    }

    @Test
    void bareIssuesArrayIsAcceptedInsteadOfRetryingFormat() {
        // 模型偶尔只给数组、漏掉 verdict 外壳：语义明确，不该为外壳烧一轮
        String bare = "[{\"need_id\": \"N1\", \"problem\": \"漏在途\", \"suggestion\": \"补 N1-E6\"}]";

        Map<String, Object> update = node(bare).apply(stateOf(bare));

        assertThat(update.get(NEED_REVIEW_RESULT)).isEqualTo(NeedReviewResult.ISSUES.name());
        assertThat(update.get(REVIEW_COUNT)).isEqualTo(1);
    }

    @Test
    void emptyIssuesArrayIsReadAsPass() {
        String bare = "[]";

        Map<String, Object> update = node(bare).apply(stateOf(bare));

        assertThat(update.get(NEED_REVIEW_RESULT)).isEqualTo(NeedReviewResult.OK.name());
    }

    @Test
    void malformedReviewOutputRetriesTheReviewNodeItself() {
        Map<String, Object> update = node("不是JSON").apply(stateOf("不是JSON"));

        assertThat(update.get(NEED_REVIEW_RESULT)).isEqualTo(NeedReviewResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_COUNT)).isEqualTo(1);
        assertThat(update.get(NEED_REVIEW_ERROR).toString()).contains("message");
        assertThat(update.get(NEED_REVIEW_OUTPUT)).isEqualTo("不是JSON");
    }

    @Test
    void exhaustedBudgetStopsTheRun() {
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                USER_REQUEST, "查库存",
                PLANNER_OUTPUT, "不是JSON",
                NEEDS, List.of(needMap()),
                REPAIR_COUNT, 2));

        Map<String, Object> update = node("不是JSON").apply(state);

        assertThat(update.get(NEED_REVIEW_RESULT)).isEqualTo(NeedReviewResult.FORMAT_RETRY_EXHAUSTED.name());
        assertThat(update.get(REPAIR_COUNT)).isEqualTo(2);
    }

    @Test
    void reviewIssuesUseTheirOwnBudgetInsteadOfTheFormatBudget() {
        // 格式修复预算已经用满，但业务审阅还有余量：不应被格式预算误杀
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                USER_REQUEST, "查库存",
                PLANNER_OUTPUT, REVIEW_ISSUES,
                NEEDS, List.of(needMap()),
                REPAIR_COUNT, 2,
                REVIEW_COUNT, 0));

        Map<String, Object> update = node(REVIEW_ISSUES).apply(state);

        assertThat(update.get(NEED_REVIEW_RESULT)).isEqualTo(NeedReviewResult.ISSUES.name());
        assertThat(update.get(REVIEW_COUNT)).isEqualTo(1);
        // 格式预算已经用满，但这次是业务漏查，两者互不影响
        assertThat(update).doesNotContainKey(REPAIR_COUNT);
    }

    @Test
    void reviewBudgetExhaustionStopsTheRun() {
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                USER_REQUEST, "查库存",
                PLANNER_OUTPUT, REVIEW_ISSUES,
                NEEDS, List.of(needMap()),
                REPAIR_COUNT, 0,
                REVIEW_COUNT, 3));

        Map<String, Object> update = node(REVIEW_ISSUES).apply(state);

        assertThat(update.get(NEED_REVIEW_RESULT)).isEqualTo(NeedReviewResult.FORMAT_RETRY_EXHAUSTED.name());
        assertThat(update.get(REVIEW_COUNT)).isEqualTo(3);
    }

    @Test
    void issuesVerdictWithoutIssuesIsTreatedAsFormatError() {
        String bad = "{\"verdict\": \"ISSUES\", \"issues\": []}";

        Map<String, Object> update = node(bad).apply(stateOf(bad));

        assertThat(update.get(NEED_REVIEW_RESULT)).isEqualTo(NeedReviewResult.RETRY_FORMAT.name());
    }

    @Test
    void reviewInputCarriesRequestNeedsAndSchema() {
        ChatModel model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(
                List.of(new Generation(new AssistantMessage(REVIEW_OK)))));
        NeedReviewNode node = new NeedReviewNode(ChatClient.create(model), "审阅提示词", mapper, 2, 3, recorder,
                messageStore, new ConversationThreads(), new VisibleResultFactory(), Clock.systemDefaultZone());

        node.apply(stateOf(REVIEW_OK));

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String content = prompt.getValue().getContents();
        assertThat(content).contains("查库存", "N1-E1", "# Table: product");
    }

    @Test
    void noPendingNeedsSkipsTheModelCall() {
        ChatModel model = mock(ChatModel.class);
        NeedReviewNode node = new NeedReviewNode(ChatClient.create(model), "审阅提示词", mapper, 2, 3, recorder,
                messageStore, new ConversationThreads(), new VisibleResultFactory(), Clock.systemDefaultZone());
        Map<String, Object> terminal = needMap();
        terminal.put("status", "DONE");
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                NEEDS, List.of(terminal)));

        Map<String, Object> update = node.apply(state);

        assertThat(update.get(NEED_REVIEW_RESULT)).isEqualTo(NeedReviewResult.OK.name());
        verify(model, org.mockito.Mockito.never()).call(any(Prompt.class));
    }
}
