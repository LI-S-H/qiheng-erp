package com.qiheng.erp.ai.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.constant.NeedCheckResult;
import com.qiheng.erp.ai.constant.NeedStatus;
import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.qiheng.erp.ai.graph.support.NeedStore;
import com.qiheng.erp.ai.graph.support.NodeMemoryRecorder;
import com.qiheng.erp.ai.service.VisibleResultFactory;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.qiheng.erp.ai.constant.GraphStateKeys.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 需求结构校验节点的契约测试。
 *
 * <p>本节点只判结构：字段完整性、依赖无环、证据项 id 唯一且带需求前缀、
 * 证据项之间的依赖与条件引用指向真实存在的证据项、证据项按执行顺序排列、
 * ADVICE 只出方案、计数不超上限。业务完整性由 need_review 审。</p>
 *
 * <p>合并规则：需求 id 没出现过就是新需求；已出现就整条替换它的证据清单，
 * 需求正文与依赖以已定稿的为准，不交回的旧证据项即删除。</p>
 */
class NeedCheckNodeTest {

    private static final ValidatorFactory VALIDATORS = Validation.buildDefaultValidatorFactory();

    private final ConversationMessageStore messageStore = mock(ConversationMessageStore.class);
    private final NodeMemoryRecorder memoryRecorder = mock(NodeMemoryRecorder.class);
    private final ObjectMapper mapper = new ObjectMapper();

    private final NeedCheckNode node = new NeedCheckNode(mapper, VALIDATORS.getValidator(), 2, 3, 6,
            memoryRecorder, messageStore, new ConversationThreads(), new VisibleResultFactory());

    @AfterAll
    static void closeValidators() {
        VALIDATORS.close();
    }

    /** 一条合法的最小需求：一条 SQL 证据项。 */
    private static String validNeed() {
        return """
                {
                  "id": "N1",
                  "intent": "DATA",
                  "need": "查当前库存",
                  "depends": [],
                  "condition": null,
                  "evidence_plan": [
                    {"id": "N1-E1", "label": "当前库存", "instruction": "查 A 物料当前库存",
                     "source": "SQL", "depends": []}
                  ]
                }
                """;
    }

    /** 已有需求重写后的产出：同一个需求 id，交回完整证据清单。 */
    private static String rewrittenNeed(String id, String itemId, String label) {
        return """
                {
                  "id": "%s",
                  "intent": "DATA",
                  "need": "查当前库存",
                  "depends": [],
                  "condition": null,
                  "evidence_plan": [
                    {"id": "%s", "label": "%s", "instruction": "查 A 物料在途采购量",
                     "source": "SQL", "depends": []}
                  ]
                }
                """.formatted(id, itemId, label);
    }

    private static String output(String needs) {
        return "{\"needs\": [" + needs + "], \"needs_clarification\": false, \"questions\": []}";
    }

    private OverAllState stateOf(String plannerOutput) {
        Map<String, Object> state = new HashMap<>();
        state.put(USER_ID, 2L);
        state.put(CONVERSATION_ID, 1L);
        state.put(REQUEST_ID, "req-1");
        state.put(PLANNER_OUTPUT, plannerOutput);
        state.put(NEEDS, List.of());
        state.put(REPAIR_COUNT, 0);
        return new OverAllState(state);
    }

    @Test
    void validNeedsAreAcceptedAndAppended() {
        Map<String, Object> update = node.apply(stateOf(output(validNeed())));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.READY.name());
        assertThat(update.get(REPAIR_COUNT)).isEqualTo(0);
        assertThat(update.get(REPAIR_ERROR)).isEqualTo(Map.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> needs = (List<Map<String, Object>>) update.get(NEEDS);
        assertThat(needs).hasSize(1);
        assertThat(needs.getFirst()).containsEntry("id", "N1")
                .containsEntry("intent", "DATA")
                .containsEntry("status", NeedStatus.PENDING.name());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{ broken",
            "null",
            "[]"
    })
    void malformedOutputRequestsRepair(String plannerOutput) {
        Map<String, Object> update = node.apply(stateOf(plannerOutput));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_COUNT)).isEqualTo(1);
        assertThat((Map<String, Object>) update.get(REPAIR_ERROR)).containsKey("message");
    }

    @Test
    void formatFailureKeepsAcceptedNeeds() {
        // 已定稿的需求是别的轮次验收过的事实，本轮产出坏了不能把它们一起清掉
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                PLANNER_OUTPUT, "{ broken",
                NEEDS, List.of(existingNeed("N1", "查当前库存", NeedStatus.PENDING)),
                REPAIR_COUNT, 0));

        Map<String, Object> update = node.apply(state);

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update).doesNotContainKey(NEEDS);
    }

    @Test
    void repairBudgetExhaustionStopsTheRun() {
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                PLANNER_OUTPUT, "{ broken",
                NEEDS, List.of(),
                REPAIR_COUNT, 2));

        Map<String, Object> update = node.apply(state);

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.FORMAT_RETRY_EXHAUSTED.name());
        assertThat(update.get(REPAIR_COUNT)).isEqualTo(2);
    }

    @Test
    void emptyEvidencePlanIsRejected() {
        // 空清单会被完成判定立刻判 DONE（空集是任何集合的子集），必须在入口拦掉
        String need = """
                {"id": "N1", "intent": "DATA", "need": "查当前库存", "depends": [],
                 "condition": null, "evidence_plan": []}
                """;

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("evidencePlan");
    }

    @Test
    void evidenceIdOwnedByAnotherNeedIsRejectedWithItsOwner() {
        // N2 里写了 N1 的证据项 id：前缀不对，而且撞了全局唯一的 key
        String first = validNeed();
        String second = """
                {"id": "N2", "intent": "DATA", "need": "判断会不会缺货", "depends": [],
                 "condition": null,
                 "evidence_plan": [{"id": "N1-E1", "label": "当前库存", "instruction": "查库存",
                                    "source": "SQL", "depends": []}]}
                """;

        Map<String, Object> update = node.apply(stateOf(output(first + ", " + second)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString())
                .contains("必须以")
                .contains("已被需求 N1 占用");
    }

    @Test
    void allProblemsAreReportedInOneRound() {
        // 一次收集全部问题：模型一轮就能改完，不必为每个错误各跑一轮修复
        String need = validNeed()
                .replace("\"N1-E1\"", "\"E1\"")
                .replace("\"condition\": null",
                        "\"condition\": {\"left\": \"evidence.N1-E9\", \"op\": \"GT\", \"right\": 0}");

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString())
                .contains("必须以")
                .contains("引用了不存在的证据项");
    }

    @Test
    void duplicatedNeedIdInOneRoundIsRejected() {
        // 同一轮把同一个需求写两遍：接口上会被当成"追加"，所以要在这里说清
        Map<String, Object> update = node.apply(stateOf(output(validNeed() + ", " + validNeed())));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("本轮重复输出需求 id N1");
    }

    @Test
    void evidenceItemIdMustBePrefixedByNeedId() {
        String need = validNeed().replace("\"N1-E1\"", "\"E1\"");

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("必须以");
    }

    @Test
    void duplicatedEvidenceItemIdIsRejected() {
        String need = validNeed().replace("\"source\": \"SQL\", \"depends\": []",
                "\"source\": \"SQL\", \"depends\": []},\n                    "
                        + "{\"id\": \"N1-E1\", \"label\": \"重复\", \"instruction\": \"重复项\", "
                        + "\"source\": \"SQL\", \"depends\": []");

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("在本需求内重复");
    }

    @Test
    void evidenceItemDependingOnUnknownItemIsRejected() {
        String need = validNeed().replace(ITEM_DEPENDS,
                "\"source\": \"SQL\", \"depends\": [\"N1-E7\"]");

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("依赖了不存在的证据项");
    }

    @Test
    void evidenceItemDependingOnItselfIsRejected() {
        String need = validNeed().replace(ITEM_DEPENDS,
                "\"source\": \"SQL\", \"depends\": [\"N1-E1\"]");

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("不能依赖自身");
    }

    /** 需求级的 depends：后面紧跟 condition，替换时不会误伤证据项的 depends。 */
    private static final String NEED_DEPENDS = "\"depends\": [],\n  \"condition\"";

    /** 证据项级的 depends：前面紧跟 source。 */
    private static final String ITEM_DEPENDS = "\"source\": \"SQL\", \"depends\": []";

    @Test
    void cyclicNeedsAreRejected() {
        String first = validNeed().replace(NEED_DEPENDS, "\"depends\": [\"N2\"],\n  \"condition\"");
        String second = """
                {
                  "id": "N2",
                  "intent": "DATA",
                  "need": "第二条",
                  "depends": ["N1"],
                  "condition": null,
                  "evidence_plan": [
                    {"id": "N2-E1", "label": "数据", "instruction": "查数据", "source": "SQL", "depends": []}
                  ]
                }
                """;

        Map<String, Object> update = node.apply(stateOf(output(first + ", " + second)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("成环");
    }

    @Test
    void needDependingOnUnknownNeedIsRejected() {
        String need = validNeed().replace(NEED_DEPENDS, "\"depends\": [\"N9\"],\n  \"condition\"");

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("依赖了不存在的需求");
    }

    @Test
    void conditionMustReferenceKnownEvidenceItem() {
        String need = validNeed().replace("\"condition\": null",
                "\"condition\": {\"left\": \"evidence.N1-E9\", \"op\": \"GT\", \"right\": 0}");

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("引用了不存在的证据项");
    }

    @Test
    void conditionMustUseEvidencePrefix() {
        String need = validNeed().replace("\"condition\": null",
                "\"condition\": {\"left\": \"N1-E1\", \"op\": \"GT\", \"right\": 0}");

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("evidence.<证据项 id>");
    }

    @Test
    void userNeedQuotaIsEnforced() {
        StringBuilder needs = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            if (i > 1) {
                needs.append(", ");
            }
            needs.append(validNeed()
                    .replace("\"id\": \"N1\"", "\"id\": \"N" + i + "\"")
                    .replace("\"N1-E1\"", "\"N" + i + "-E1\""));
        }

        Map<String, Object> update = node.apply(stateOf(output(needs.toString())));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("同时未完成的用户需求");
    }

    @Test
    void derivedNeedsAreCountedSeparately() {
        StringBuilder needs = new StringBuilder();
        for (int i = 1; i <= 7; i++) {
            if (i > 1) {
                needs.append(", ");
            }
            needs.append(validNeed().replace("\"id\": \"N1\"", "\"id\": \"D" + i + "\"")
                    .replace("\"N1-E1\"", "\"D" + i + "-E1\""));
        }

        Map<String, Object> update = node.apply(stateOf(output(needs.toString())));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("同时未完成的派生需求");
    }

    @Test
    void clarificationRequestPausesInsteadOfValidating() {
        String plannerOutput = """
                {"needs": [], "needs_clarification": true,
                 "questions": [{"id": "period", "question": "用哪个周期？", "options": ["本月", "上月"]}]}
                """;

        Map<String, Object> update = node.apply(stateOf(plannerOutput));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.WAITING_HUMAN.name());
        assertThat(update.get(PAUSE_ID).toString()).isNotBlank();
        assertThat((List<?>) update.get(CLARIFICATION_QUESTIONS)).hasSize(1);
    }

    @Test
    void clarificationWithEmptyQuestionsIsRejected() {
        String plannerOutput = """
                {"needs": [], "needs_clarification": true, "questions": []}
                """;

        Map<String, Object> update = node.apply(stateOf(plannerOutput));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
    }

    @Test
    void rewritingEvidencePlanKeepsTheFinalizedNeedBody() {
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                PLANNER_OUTPUT, output(rewrittenNeed("N1", "N1-E2", "在途采购量")),
                NEEDS, List.of(existingNeed("N1", "旧描述", NeedStatus.PENDING)),
                REPAIR_COUNT, 0));

        Map<String, Object> update = node.apply(state);

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.READY.name());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> needs = (List<Map<String, Object>>) update.get(NEEDS);
        assertThat(needs).hasSize(1);
        // 需求正文以已定稿的为准，模型这轮写什么不影响它
        assertThat(needs.getFirst()).containsEntry("need", "旧描述")
                .containsEntry("status", NeedStatus.PENDING.name());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) needs.getFirst().get("evidence_plan");
        // 整条替换：旧清单里的 N1-E1 没交回，就是被删掉了
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("id", "N1-E2");
    }

    @Test
    void resendingTheSameEvidenceItemIsAccepted() {
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                PLANNER_OUTPUT, output(validNeed()),
                NEEDS, List.of(existingNeed("N1", "查当前库存", NeedStatus.PENDING)),
                REPAIR_COUNT, 0));

        Map<String, Object> update = node.apply(state);

        // 重写轮要交完整清单，重发保留项是必须的
        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.READY.name());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> needs = (List<Map<String, Object>>) update.get(NEEDS);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items =
                (List<Map<String, Object>>) needs.getFirst().get("evidence_plan");
        assertThat(items).hasSize(1);
    }

    @Test
    void rewritingTerminalNeedKeepsItsStatus() {
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                PLANNER_OUTPUT, output(rewrittenNeed("N1", "N1-E2", "在途采购量")),
                NEEDS, List.of(existingNeed("N1", "查当前库存", NeedStatus.DONE)),
                REPAIR_COUNT, 0));

        Map<String, Object> update = node.apply(state);

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.READY.name());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> needs = (List<Map<String, Object>>) update.get(NEEDS);
        assertThat(needs.getFirst()).containsEntry("status", NeedStatus.DONE.name());
    }

    @Test
    void rewritingPlanUpdatesConditionAlongWithEvidenceIds() {
        // 实测踩到的坑：证据项换 id 后，若 condition 仍指向旧 id 就是悬空引用。
        // condition 属于"计划"的一部分，必须跟证据清单一起换。
        String first = """
                {"needs":[
                  {"id":"N1","intent":"DATA","need":"查当前库存","depends":[],"condition":null,
                   "evidence_plan":[
                     {"id":"N1-E1","label":"库存","instruction":"查库存","source":"SQL","depends":[]},
                     {"id":"N1-E5","label":"缺口","instruction":"用 N1-E1 算缺口","source":"PYTHON","depends":["N1-E1"]}]},
                  {"id":"N2","intent":"ADVICE","need":"给出补货方案","depends":[],
                   "condition":{"left":"evidence.N1-E5","op":"GT","right":0},
                   "evidence_plan":[{"id":"N2-E1","label":"方案","instruction":"给出补货方案",
                                     "source":"PROPOSAL","depends":["N1-E5"]}]}
                ],"needs_clarification":false,"questions":[]}
                """;
        Map<String, Object> accepted = node.apply(stateOf(first));
        assertThat(accepted.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.READY.name());

        // 重写：N1 换掉 E5，N2 的 condition 与 depends 一起改到新 id
        String second = """
                {"needs":[
                  {"id":"N1","intent":"DATA","need":"查当前库存","depends":[],"condition":null,
                   "evidence_plan":[
                     {"id":"N1-E1","label":"库存","instruction":"查库存","source":"SQL","depends":[]},
                     {"id":"N1-E6","label":"缺口","instruction":"用 N1-E1 算缺口","source":"PYTHON","depends":["N1-E1"]}]},
                  {"id":"N2","intent":"ADVICE","need":"给出补货方案","depends":[],
                   "condition":{"left":"evidence.N1-E6","op":"GT","right":0},
                   "evidence_plan":[{"id":"N2-E2","label":"方案","instruction":"给出补货方案",
                                     "source":"PROPOSAL","depends":["N1-E6"]}]}
                ],"needs_clarification":false,"questions":[]}
                """;
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-2",
                PLANNER_OUTPUT, second, NEEDS, accepted.get(NEEDS), REPAIR_COUNT, 0));

        Map<String, Object> updated = node.apply(state);

        assertThat(updated.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.READY.name());
        assertThat(updated.get(NEEDS).toString())
                .contains("N1-E6")
                .doesNotContain("N1-E5")
                .doesNotContain("N2-E1");
    }

    @Test
    void droppingAnItemReferencedByAnotherNeedNamesTheReferrer() {
        // 重写 N1 时删掉了被 N2 引用的 N1-E4：只说"不存在"模型不知道该改谁，要点名
        String first = """
                {"needs":[
                  {"id":"N1","intent":"DATA","need":"查当前库存","depends":[],"condition":null,
                   "evidence_plan":[
                     {"id":"N1-E1","label":"库存","instruction":"查库存","source":"SQL","depends":[]},
                     {"id":"N1-E4","label":"缺口","instruction":"用 N1-E1 算缺口","source":"PYTHON","depends":["N1-E1"]}]},
                  {"id":"N2","intent":"ADVICE","need":"给出补货方案","depends":[],
                   "condition":null,
                   "evidence_plan":[{"id":"N2-E1","label":"方案","instruction":"给出补货方案",
                                     "source":"PROPOSAL","depends":["N1-E4"]}]}
                ],"needs_clarification":false,"questions":[]}
                """;
        Map<String, Object> accepted = node.apply(stateOf(first));
        assertThat(accepted.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.READY.name());

        // 第二轮只重写 N1，删掉 N1-E4，N2 的引用没跟着改
        String second = """
                {"needs":[
                  {"id":"N1","intent":"DATA","need":"查当前库存","depends":[],"condition":null,
                   "evidence_plan":[
                     {"id":"N1-E1","label":"库存","instruction":"查库存","source":"SQL","depends":[]}]}
                ],"needs_clarification":false,"questions":[]}
                """;
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-2",
                PLANNER_OUTPUT, second, NEEDS, accepted.get(NEEDS), REPAIR_COUNT, 0));

        Map<String, Object> update = node.apply(state);

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString())
                .contains("N1-E4")
                .contains("被需求 N2 引用")
                .contains("同轮把引用改成新 id");
    }



    @Test
    void proposalDraftIsPreferredOverPlannerDraft() {
        // 两份草案并存时以方案子图那份为准，planner 那份是陈旧草案
        String plannerDraft = output(validNeed());
        String proposalDraft = output("""
                {"id": "D1", "intent": "DATA", "need": "把方案说清楚", "depends": [],
                 "condition": null,
                 "evidence_plan": [{"id": "D1-E1", "label": "调拨成本", "instruction": "查调拨成本",
                                    "source": "SQL", "depends": []}]}
                """);
        OverAllState state = new OverAllState(Map.of(
                USER_ID, 2L, CONVERSATION_ID, 1L, REQUEST_ID, "req-1",
                PLANNER_OUTPUT, plannerDraft,
                PROPOSAL_OUTPUT, proposalDraft,
                NEEDS, List.of(), REPAIR_COUNT, 0));

        Map<String, Object> update = node.apply(state);

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.READY.name());
        assertThat(update.get(NEEDS).toString()).contains("D1-E1").doesNotContain("N1-E1");
    }

    @Test
    void instructionWithSchemaColumnIsRejected() {
        // instruction 写业务口径；表名/字段名一旦写进来，就是 planner 替取数子图做了选择
        String need = validNeed().replace("查 A 物料当前库存",
                "查询 warehouse_stock 表，取 stock_qty - locked_qty 得到可用库存，deleted=0");

        Map<String, Object> update = node.apply(stateWithSchema(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString())
                .contains("warehouse_stock").contains("stock_qty").contains("业务口径");
    }

    @Test
    void instructionWithEnumLiteralsIsAccepted() {
        // 状态枚举字面量是业务口径的一部分，不能被字段名守卫误杀
        String need = validNeed().replace("查 A 物料当前库存",
                "给出 A 物料的在途采购量，只统计状态为 APPROVED 或 PARTIAL_INBOUND 的采购单剩余未入库量");

        Map<String, Object> update = node.apply(stateWithSchema(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.READY.name());
    }

    @Test
    void oversizedProposalInstructionIsRejected() {
        // PROPOSAL 的 instruction 是交接注意事项；写长基本就是在替子图定手段
        String longNote = "需考虑降价促销、捆绑销售、退回供应商或报废等常见手段，并评估对利润和现金流的影响。".repeat(3);
        String need = """
                {"id": "N2", "intent": "ADVICE", "need": "给出清库方案", "depends": [],
                 "condition": null,
                 "evidence_plan": [{"id": "N2-E1", "label": "清库方案候选清单",
                                    "instruction": "%s", "source": "PROPOSAL", "depends": []}]}
                """.formatted(longNote);

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("无特殊约束");
    }

    @Test
    void conditionComparingEmptyStringIsRejected() {
        // 实测坑：拿 NEQ "" 变相判"清单非空"，比较器只会判类型不一致，条件永远不成立
        String need = validNeed().replace("\"condition\": null",
                "\"condition\": {\"left\": \"evidence.N1-E1\", \"op\": \"NEQ\", \"right\": \"\"}");

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("空字符串");
    }

    @Test
    void conditionReferencingListResultIsRejected() {
        // 比较器只能比标量；引清单就要求先加一条数量项
        String need = validNeed().replace("\"label\": \"当前库存\"", "\"label\": \"低库存产品清单\"")
                .replace("\"condition\": null",
                        "\"condition\": {\"left\": \"evidence.N1-E1\", \"op\": \"GT\", \"right\": 0}");

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("清单类结果").contains("数量证据项");
    }

    @Test
    void conditionReferencingCountItemIsAccepted() {
        String need = validNeed().replace("\"label\": \"当前库存\"", "\"label\": \"低库存产品数量\"")
                .replace("\"condition\": null",
                        "\"condition\": {\"left\": \"evidence.N1-E1\", \"op\": \"GT\", \"right\": 0}");

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.READY.name());
    }

    /** 带授权表结构的状态：措辞守卫只在有 schema 时生效。 */
    private OverAllState stateWithSchema(String plannerOutput) {
        Map<String, Object> state = new HashMap<>();
        state.put(USER_ID, 2L);
        state.put(CONVERSATION_ID, 1L);
        state.put(REQUEST_ID, "req-1");
        state.put(PLANNER_OUTPUT, plannerOutput);
        state.put(NEEDS, List.of());
        state.put(REPAIR_COUNT, 0);
        state.put(AUTHORIZED_SCHEMA_REF, Map.of("schema", """
                # Table: warehouse_stock, 库存余额
                [
                (warehouse_id:bigint, 仓库ID),
                (stock_qty:bigint, 当前库存数量),
                (locked_qty:bigint, 锁定库存数量),
                (create_time:datetime, 创建时间)
                ]
                """));
        return new OverAllState(state);
    }

    @Test
    void adviceNeedWithSingleProposalItemIsAccepted() {
        // ADVICE 只出方案：一条 PROPOSAL 项，自己不查不算
        String need = """
                {"id": "N2", "intent": "ADVICE", "need": "给出补货方案", "depends": [],
                 "condition": null,
                 "evidence_plan": [{"id": "N2-E1", "label": "补货方案候选清单",
                                    "instruction": "给出调拨或采购方案，两者可组合",
                                    "source": "PROPOSAL", "depends": []}]}
                """;

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.READY.name());
    }

    @Test
    void adviceNeedWithExtraEvidenceItemsIsRejected() {
        // 把方案拆成 SQL/PYTHON 自己算，就是本次 N2 膨胀到 14 项的病根
        String need = """
                {"id": "N2", "intent": "ADVICE", "need": "给出补货方案", "depends": [],
                 "condition": null,
                 "evidence_plan": [
                   {"id": "N2-E1", "label": "补货方案候选清单", "instruction": "给出方案",
                    "source": "PROPOSAL", "depends": []},
                   {"id": "N2-E2", "label": "建议补货量", "instruction": "算补货量",
                    "source": "PYTHON", "depends": ["N2-E1"]}]}
                """;

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("只能有一条 source=PROPOSAL");
    }

    @Test
    void dataNeedWithProposalItemIsRejected() {
        String need = """
                {"id": "N1", "intent": "DATA", "need": "查当前库存", "depends": [],
                 "condition": null,
                 "evidence_plan": [{"id": "N1-E1", "label": "方案", "instruction": "出方案",
                                    "source": "PROPOSAL", "depends": []}]}
                """;

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("是 DATA，不能有");
    }

    @Test
    void crossNeedEvidenceReferenceIsAccepted() {
        // 引用即依赖：N2 引用了 N1 的证据项，代码据此推出 N2 要等 N1
        String first = validNeed();
        String second = """
                {"id": "N2", "intent": "DATA", "need": "用库存判断会不会缺货", "depends": [],
                 "condition": null,
                 "evidence_plan": [{"id": "N2-E1", "label": "缺口", "instruction": "用当前库存算缺口",
                                    "source": "PYTHON", "depends": ["N1-E1"]}]}
                """;

        Map<String, Object> update = node.apply(stateOf(output(first + ", " + second)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.READY.name());
    }

    @Test
    void crossNeedReferenceFormingACycleIsRejected() {
        // N1 声明等 N2，N2 的证据项又引用 N1 的证据项：反向依赖，等价于成环
        String first = validNeed().replace(NEED_DEPENDS, "\"depends\": [\"N2\"],\n  \"condition\"");
        String second = """
                {"id": "N2", "intent": "DATA", "need": "用库存判断缺口", "depends": [],
                 "condition": null,
                 "evidence_plan": [{"id": "N2-E1", "label": "缺口", "instruction": "用当前库存算缺口",
                                    "source": "PYTHON", "depends": ["N1-E1"]}]}
                """;

        Map<String, Object> update = node.apply(stateOf(output(first + ", " + second)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("成环");
    }

    @Test
    void dependencyMustBeDeclaredBeforeItsDependent() {
        // 数组顺序即执行顺序：被依赖的项必须写在前面
        String need = validNeed().replace("\"source\": \"SQL\", \"depends\": []",
                "\"source\": \"PYTHON\", \"depends\": [\"N1-E2\"]},\n                    "
                        + "{\"id\": \"N1-E2\", \"label\": \"输入\", \"instruction\": \"查数\", "
                        + "\"source\": \"SQL\", \"depends\": []");

        Map<String, Object> update = node.apply(stateOf(output(need)));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.RETRY_FORMAT.name());
        assertThat(update.get(REPAIR_ERROR).toString()).contains("排在它后面");
    }

    @Test
    void echoedMarkerIsStrippedBeforeParsing() {
        // 模型会模仿历史行首的标记：[输出]{"needs":...} 不能当成 JSON 语法错误
        Map<String, Object> update = node.apply(stateOf("[输出] " + output(validNeed())));

        assertThat(update.get(NEED_CHECK_RESULT)).isEqualTo(NeedCheckResult.READY.name());
    }

    /**
     * 造一条已存在的需求：结构与真实需求一致，只改描述与状态。
     */
    private Map<String, Object> existingNeed(String id, String need, NeedStatus status) {
        try {
            String json = validNeed().replace("\"查当前库存\"", "\"" + need + "\"");
            Map<String, Object> map = mapper.convertValue(mapper.readTree(json), Map.class);
            map.put("status", status.name());
            return map;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("测试夹具 JSON 解析失败", error);
        }
    }
}
