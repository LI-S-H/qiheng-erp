package com.qiheng.erp.ai.graph.runtime;

import com.qiheng.erp.ai.graph.support.ConversationExecutionLock;
import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.qiheng.erp.ai.graph.support.NodeMemoryRecorder;
import com.qiheng.erp.ai.graph.runtime.RunCoordinator;
import com.qiheng.erp.ai.graph.runtime.RunStartPreparer;
import com.qiheng.erp.ai.graph.runtime.RunResumePreparer;
import com.qiheng.erp.ai.graph.runtime.RunFailureHandler;
import com.qiheng.erp.ai.graph.runtime.RunStateResolver;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.graph.GraphTopology;
import com.qiheng.erp.ai.config.RedisSaverConfiguration;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.constant.NeedCheckResult;
import com.qiheng.erp.ai.constant.NeedReviewResult;
import com.qiheng.erp.ai.domain.dto.GraphResumeRequest;
import com.qiheng.erp.ai.domain.dto.GraphStartRequest;
import com.qiheng.erp.ai.domain.dto.needs.ClarificationQuestion;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.vo.RunArtifactEventVO;
import com.qiheng.erp.ai.domain.vo.RunFinishedEventVO;
import com.qiheng.erp.ai.domain.vo.RunStoppedEventVO;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.service.VisibleMessageCodec;
import com.qiheng.erp.ai.service.VisibleResultFactory;
import com.qiheng.erp.ai.service.knowledge.AuthorizedKnowledgeProvider;
import com.qiheng.erp.ai.service.impl.GraphServiceImpl;
import com.qiheng.erp.ai.service.progress.RunProgressPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import com.qiheng.erp.ai.service.data.registry.StubSchemaProvider;
import com.qiheng.erp.ai.graph.node.DataAccessNode;
import com.qiheng.erp.ai.graph.node.NeedCheckNode;
import com.qiheng.erp.ai.graph.node.NeedReviewNode;
import com.qiheng.erp.ai.graph.node.KnowledgeNode;
import com.qiheng.erp.ai.graph.node.NeedSelectorNode;
import com.qiheng.erp.ai.graph.node.PlannerNode;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.MockedStatic;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.qiheng.erp.ai.constant.GraphStateKeys.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 显式使用 -Dai.redis.it=true 启用；模型响应由脚本提供，不调用外部模型。
 * 使用官方 RedisSaver 和生产拓扑，清理范围仅限本测试实际创建的随机线程。
 */
@EnabledIfSystemProperty(named = "ai.redis.it", matches = "true")
class GraphRedisIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"), ZoneOffset.UTC);
    private static final long TEST_CONVERSATION_ID = 1L;

    private static RedissonClient redis;
    private static ValidatorFactory validators;
    private static String plannerPrompt;
    private static String reviewPrompt;

    private final List<String> ownedThreads = new ArrayList<>();

    /** 最近一次 graph() 装配的节点记忆写入桩：用于断言"哪些拍会写记忆"。 */
    private NodeMemoryRecorder nodeMemoryRecorder;

    @BeforeAll
    static void connectRedis() throws Exception {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(System.getProperty("ai.redis.address", "redis://127.0.0.1:16379"))
                .setDatabase(Integer.getInteger("ai.redis.database", 15))
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(2)
                .setConnectTimeout(2000)
                .setTimeout(3000)
                .setRetryAttempts(0);
        redis = Redisson.create(config);
        validators = Validation.buildDefaultValidatorFactory();
        plannerPrompt = new ClassPathResource("prompts/planner.md").getContentAsString(StandardCharsets.UTF_8);
        reviewPrompt = new ClassPathResource("prompts/need_review.md").getContentAsString(StandardCharsets.UTF_8);
    }

    @AfterEach
    void deleteOnlyOwnedCheckpoints() {
        for (String thread : ownedThreads) {
            // 官方 Saver 使用内部 UUID 保存内容；从当前测试线程的元数据精确定位，禁止扫描或清库。
            String metaKey = "graph:thread:meta:" + thread;
            String internalId = redis.<String, String>getMap(metaKey).get("thread_id");
            if (internalId != null) {
                redis.getKeys().delete(
                        "graph:checkpoint:content:" + internalId,
                        "graph:thread:reverse:" + internalId,
                        metaKey);
            }
        }
    }

    @AfterAll
    static void closeResources() {
        if (validators != null) {
            validators.close();
        }
        if (redis != null) {
            redis.shutdown();
        }
    }

    @Test
    void readyRunPublishesArtifactBeforeRunFinished() throws Exception {
        ScriptedModel model = new ScriptedModel(readyNeeds(), reviewOk());
        CompiledGraph graph = graph(model, 2);
        List<ServerSentEvent<Object>> frames;
        try (MockedStatic<UserContext> users = login(101L)) {
            frames = collect(service(graph).stream(new GraphStartRequest(TEST_CONVERSATION_ID, "统计2026年9月各商品销量")));
        }
        threadOf(101L);

        // 发布拍先于终态：前端在 run_finished 之前就能看到需求内容
        RunArtifactEventVO artifact = needsArtifactEvent(frames);
        assertThat(artifact.type()).isEqualTo("NEEDS");
        assertThat(artifact.checkpointId()).isNotBlank();
        assertThat(artifact.content()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> artifactContent = (Map<String, Object>) artifact.content();
        assertThat(artifactContent).containsEntry("type", "NEEDS");
        assertThat(eventIndex(frames, RunProgressPublisher.EVENT_RUN_FINISHED))
                .isGreaterThan(eventIndex(frames, RunProgressPublisher.EVENT_ARTIFACT));
        RunFinishedEventVO finished = finishedEvent(frames);
        assertThat(finished.status()).isEqualTo("NEEDS_READY");
        assertThat(finished.warning()).isNull();
        assertThat(endStatus(frames)).isEqualTo("NEEDS_READY");
        assertThat(model.calls()).isEqualTo(2);
    }

    @Test
    void repairsMalformedJsonUsingDetailedErrorAndResetsBudget() throws Exception {
        ScriptedModel model = new ScriptedModel("{\"needs\": }", readyNeeds(), reviewOk());
        CompiledGraph graph = graph(model, 2);
        List<ServerSentEvent<Object>> frames;
        try (MockedStatic<UserContext> users = login(101L)) {
            frames = collect(service(graph).stream(new GraphStartRequest(TEST_CONVERSATION_ID, "统计2026年9月各商品销量")));
        }
        String thread = threadOf(101L);

        assertThat(endStatus(frames)).isEqualTo("NEEDS_READY");
        assertThat(model.calls()).isEqualTo(3);
        // 错误明细要回到 planner 的提示词里；上一轮输出不再重复注入（它在历史中）
        assertThat(model.prompts.get(1)).contains(
                "## 具体错误", "JsonParseException", "\"line\":1", "\"column\":11");
        StateSnapshot persisted = graph.getState(threadConfig(thread));
        assertThat(persisted.next()).isEqualTo(END);
        assertThat(((Number) persisted.state().value(REPAIR_COUNT, 0)).intValue()).isZero();
        assertThat(persisted.state().value(REPAIR_ERROR, Map.of())).isEmpty();
        List<?> needs = persisted.state().value(NEEDS, List.of());
        assertThat(needs).hasSize(1);
        assertThat(needs.getFirst().toString()).contains("N1");
    }

    @Test
    void stopsAfterInitialCallAndTwoFailedFormatRepairs() throws Exception {
        ScriptedModel model = new ScriptedModel("不是JSON", "仍然不是JSON", "第三次也不是JSON");
        CompiledGraph graph = graph(model, 2);
        List<ServerSentEvent<Object>> frames;
        try (MockedStatic<UserContext> users = login(102L)) {
            frames = collect(service(graph).stream(new GraphStartRequest(TEST_CONVERSATION_ID, "分析商品销量")));
        }
        String thread = threadOf(102L);

        assertThat(endStatus(frames)).isEqualTo("FORMAT_RETRY_EXHAUSTED");
        ServerSentEvent<Object> terminal = terminal(frames);
        assertThat(terminal.event()).isEqualTo(RunProgressPublisher.EVENT_RUN_STOPPED);
        RunStoppedEventVO stopped = MAPPER.convertValue(terminal.data(), RunStoppedEventVO.class);
        assertThat(stopped.reason()).isNull();
        assertThat(stopped.warning()).isNull();
        StateSnapshot persisted = graph.getState(threadConfig(thread));
        assertThat(((Number) persisted.state().value(REPAIR_COUNT, 0)).intValue()).isEqualTo(2);
        assertThat(persisted.state().value(NEEDS, List.of())).isEmpty();
        assertThat(persisted.state().value(REPAIR_ERROR, Map.of()))
                .containsKeys("type", "message", "line", "column");
        assertThat(model.calls()).isEqualTo(3);
        assertThat(model.prompts.get(2)).contains("仍然不是JSON");
    }

    @Test
    void reviewIssuesGoBackToPlannerWithFeedback() throws Exception {
        // 第一次审阅提问题，planner 修复后再审通过
        ScriptedModel model = new ScriptedModel(readyNeeds(), reviewIssues(), rewrittenWithInTransit(), reviewOk());
        CompiledGraph graph = graph(model, 2);
        List<ServerSentEvent<Object>> frames;
        try (MockedStatic<UserContext> users = login(106L)) {
            frames = collect(service(graph).stream(new GraphStartRequest(TEST_CONVERSATION_ID, "判断是否缺货")));
        }
        String thread = threadOf(106L);

        assertThat(endStatus(frames)).isEqualTo("NEEDS_READY");
        assertThat(model.calls()).isEqualTo(4);
        // 审阅问题必须回到 planner 的提示词里
        assertThat(model.prompts.get(2)).contains("漏查在途采购量");
        assertThat(model.prompts.get(2)).contains("N1-E2");
        // 重写后清单同时包含保留项与新项
        List<?> needs = graph.getState(threadConfig(thread)).state().value(NEEDS, List.of());
        assertThat(needs).hasSize(1);
        assertThat(needs.getFirst().toString()).contains("N1-E1", "N1-E2");
    }

    @Test
    void restoresAcrossGraphInstancesSupportsRepeatedHumanAndRejectsInvalidResumes() throws Exception {
        // 每次有效澄清都应重置格式预算：两轮各允许一次修复，而不是整个任务共用一次。
        ScriptedModel firstModel = new ScriptedModel("坏JSON", clarification("分析哪个月份？"));
        CompiledGraph firstGraph = graph(firstModel, 1);
        NodeMemoryRecorder firstRecorder = nodeMemoryRecorder;
        String thread = threadOf(104L);
        List<ServerSentEvent<Object>> firstFrames;
        try (MockedStatic<UserContext> users = login(104L)) {
            firstFrames = collect(service(firstGraph).stream(new GraphStartRequest(TEST_CONVERSATION_ID, "分析销售并明确范围")));
        }
        assertThat(endStatus(firstFrames)).isEqualTo("WAITING_HUMAN");
        RunArtifactEventVO firstClarification = clarificationEvent(firstFrames);
        assertThat(firstClarification.type()).isEqualTo("CLARIFICATION");
        String firstPauseId = pauseIdOf(firstClarification);
        assertThat(firstPauseId).isNotBlank();
        assertThat(((Number) firstGraph.getState(threadConfig(thread)).state()
                .value(REPAIR_COUNT, 0)).intValue()).isZero();
        assertThat(firstModel.calls()).isEqualTo(2);
        assertThat(firstGraph.getState(threadConfig(thread)).next()).isEqualTo(HUMAN_FEEDBACK_NODE);
        assertThat(firstGraph.getState(threadConfig(thread)).state().value(PAUSE_ID, "")).isEqualTo(firstPauseId);

        // 重新创建 Saver、Graph、Service，确保恢复依赖 Redis，而不是对象里的临时记忆。
        ScriptedModel resumedModel = new ScriptedModel("再次坏JSON", clarification("统计哪个仓库？"),
                readyNeeds(), reviewOk());
        CompiledGraph resumedGraph = graph(resumedModel, 1);
        NodeMemoryRecorder resumedRecorder = nodeMemoryRecorder;
        GraphServiceImpl resumedService = service(resumedGraph);
        String secondPauseId;
        try (MockedStatic<UserContext> users = login(104L)) {
            assertThat(resumeStreamError(resumedService, resume(thread, firstPauseId, "  "))).contains("不能为空");
            // 无效提交不消费暂停授权：原暂停点仍可用
            assertThat(resumedGraph.getState(threadConfig(thread)).state().value(PAUSE_ID, "")).isEqualTo(firstPauseId);

            List<ServerSentEvent<Object>> secondFrames = collect(
                    resumedService.streamResume(resume(thread, firstPauseId, "2026年9月")));
            assertThat(endStatus(secondFrames)).isEqualTo("WAITING_HUMAN");
            RunArtifactEventVO secondClarification = clarificationEvent(secondFrames);
            secondPauseId = pauseIdOf(secondClarification);
            assertThat(secondPauseId).isNotBlank();
            assertThat(secondPauseId).isNotEqualTo(firstPauseId);
            assertThat(resumedGraph.getState(threadConfig(thread)).state().value(PAUSE_ID, "")).isEqualTo(secondPauseId);
            assertThat(resumeStreamError(resumedService, resume(thread, firstPauseId, "旧页面重复回答"))).contains("过期");

            List<ServerSentEvent<Object>> readyFrames = collect(
                    resumedService.streamResume(resume(thread, secondPauseId, "上海仓")));
            assertThat(endStatus(readyFrames)).isEqualTo("NEEDS_READY");
            assertThat(((Number) resumedGraph.getState(threadConfig(thread)).state()
                    .value(REPAIR_COUNT, 0)).intValue()).isZero();
            assertThat(resumedGraph.getState(threadConfig(thread)).state().value(PAUSE_ID, "")).isEmpty();
            assertThat(resumeStreamError(resumedService, resume(thread, secondPauseId, "再次提交"))).contains("不在等待");

            StateSnapshot completed = resumedGraph.getState(threadConfig(thread));
            List<?> history = completed.state().value(CLARIFICATION_HISTORY, List.of());
            assertThat(history).hasSize(2);
            assertThat(history.get(0).toString()).contains("分析哪个月份", "2026年9月");
            assertThat(history.get(1).toString()).contains("统计哪个仓库", "上海仓");
            assertThat(completed.state().value(HUMAN_FEEDBACK, "")).isEmpty();
            // 最后一轮 planner 提示词要带上原始需求与两轮回答
            assertThat(lastPlannerPrompt(resumedModel.prompts))
                    .contains("分析销售并明确范围", "2026年9月", "上海仓")
                    .doesNotContain("本次为输出格式修复");
        }
        try (MockedStatic<UserContext> users = login(999L)) {
            assertThat(resumeStreamError(resumedService, resume(thread, firstPauseId, "跨用户回答"))).isNotBlank();
        }
        verify(firstRecorder, never()).acceptedNeeds(anyLong(), anyLong(), any(), any());
        verify(resumedRecorder, times(1)).acceptedNeeds(anyLong(), anyLong(), any(), any());
        verify(resumedRecorder).clarificationAsked(anyLong(), anyLong(), any(), eq(secondPauseId), any());
        verify(resumedRecorder).clarificationAnswered(anyLong(), anyLong(), any(), eq(firstPauseId), eq("2026年9月"));
        verify(resumedRecorder).clarificationAnswered(anyLong(), anyLong(), any(), eq(secondPauseId), eq("上海仓"));
    }

    @Test
    void newTaskOnSameThreadResetsClarificationHistoryAndRoundNo() throws Exception {
        // 一个脚本覆盖两个任务：任务一「澄清 → 出需求 → 审阅通过」，任务二「出需求 → 审阅通过」
        ScriptedModel model = new ScriptedModel(
                clarification("统计哪个仓库？"), readyNeeds(), reviewOk(),
                readyNeeds(), reviewOk());
        CompiledGraph graph = graph(model, 2);
        String thread = threadOf(108L);

        try (MockedStatic<UserContext> users = login(108L)) {
            List<ServerSentEvent<Object>> firstFrames = collect(
                    service(graph).stream(new GraphStartRequest(TEST_CONVERSATION_ID, "统计销量")));
            String pauseId = pauseIdOf(clarificationEvent(firstFrames));
            assertThat(endStatus(collect(service(graph).streamResume(resume(thread, pauseId, "上海仓")))))
                    .isEqualTo("NEEDS_READY");

            // 任务内累积：一次澄清后历史里有 1 条，第二轮 planner 的轮次号是 2
            StateSnapshot afterFirst = graph.getState(threadConfig(thread));
            assertThat(afterFirst.state().value(CLARIFICATION_HISTORY, List.of())).hasSize(1);
            assertThat(((Number) afterFirst.state().value(ROUND_NO, 0)).intValue()).isEqualTo(2);

            // 同一会话同一线程重新 start：澄清历史与轮次号必须清零，不能把上一个任务的问答带进新任务
            List<ServerSentEvent<Object>> secondFrames = collect(
                    service(graph).stream(new GraphStartRequest(TEST_CONVERSATION_ID, "统计采购金额")));
            assertThat(endStatus(secondFrames)).isEqualTo("NEEDS_READY");

            StateSnapshot afterSecond = graph.getState(threadConfig(thread));
            assertThat(afterSecond.state().value(CLARIFICATION_HISTORY, List.of())).isEmpty();
            assertThat(((Number) afterSecond.state().value(ROUND_NO, 0)).intValue()).isEqualTo(1);
            // 新任务的第一轮提示词里不应再出现上一个任务的澄清段
            assertThat(lastPlannerPrompt(model.prompts)).doesNotContain("## 已确认的澄清");
        }
    }

    @Test
    void rejectsUnknownThreadWithoutCallingModel() throws Exception {
        ScriptedModel model = new ScriptedModel();
        GraphServiceImpl service = service(graph(model, 2));
        try (MockedStatic<UserContext> users = login(105L)) {
            GraphResumeRequest missing = new GraphResumeRequest(
                    "erp:ai:graph:v1:105:999999", "missing", "补充内容");
            assertThat(resumeStreamError(service, missing)).contains("不存在");
        }
        assertThat(model.calls()).isZero();
    }

    /**
     * 最后一轮 planner 提示词：审阅节点也有模型调用，不能按"最后一次调用"取。
     */
    private static String lastPlannerPrompt(List<String> prompts) {
        for (int i = prompts.size() - 1; i >= 0; i--) {
            if (prompts.get(i).contains("## 原始需求")) {
                return prompts.get(i);
            }
        }
        throw new AssertionError("没有找到 planner 提示词");
    }

    /** 收集事件流：blockLast 正常结束即本轮收口，异常照旧抛出。 */
    private static List<ServerSentEvent<Object>> collect(Flux<ServerSentEvent<Object>> stream) {
        List<ServerSentEvent<Object>> frames = new CopyOnWriteArrayList<>();
        stream.doOnNext(frames::add).blockLast();
        return frames;
    }

    /** 每次执行只能有一个终结事件：暂停轮以 clarification_required 收口，其余为 run_finished 或 run_stopped。 */
    private static ServerSentEvent<Object> terminal(List<ServerSentEvent<Object>> frames) {
        List<ServerSentEvent<Object>> terminals = frames.stream()
                .filter(sse -> RunProgressPublisher.EVENT_RUN_FINISHED.equals(sse.event())
                        || RunProgressPublisher.EVENT_RUN_STOPPED.equals(sse.event())
                        || RunProgressPublisher.EVENT_CLARIFICATION_REQUIRED.equals(sse.event()))
                .toList();
        assertThat(terminals).hasSize(1);
        return terminals.getFirst();
    }

    /** 从事件流还原本轮终态状态：暂停轮由事件名判定，其余取薄载荷 status。 */
    private static String endStatus(List<ServerSentEvent<Object>> frames) {
        ServerSentEvent<Object> terminal = terminal(frames);
        if (RunProgressPublisher.EVENT_CLARIFICATION_REQUIRED.equals(terminal.event())) {
            return "WAITING_HUMAN";
        }
        if (RunProgressPublisher.EVENT_RUN_FINISHED.equals(terminal.event())) {
            return MAPPER.convertValue(terminal.data(), RunFinishedEventVO.class).status();
        }
        return MAPPER.convertValue(terminal.data(), RunStoppedEventVO.class).status();
    }

    private static RunFinishedEventVO finishedEvent(List<ServerSentEvent<Object>> frames) {
        ServerSentEvent<Object> frame = frames.stream()
                .filter(sse -> RunProgressPublisher.EVENT_RUN_FINISHED.equals(sse.event()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未收到 run_finished 事件"));
        return MAPPER.convertValue(frame.data(), RunFinishedEventVO.class);
    }

    /** 澄清事件载荷：顶层 checkpointId 由服务端从快照补齐。 */
    private static RunArtifactEventVO clarificationEvent(List<ServerSentEvent<Object>> frames) {
        ServerSentEvent<Object> frame = frames.stream()
                .filter(sse -> RunProgressPublisher.EVENT_CLARIFICATION_REQUIRED.equals(sse.event()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未收到 clarification_required 事件"));
        return MAPPER.convertValue(frame.data(), RunArtifactEventVO.class);
    }

    /** 澄清事件载荷里的 pauseId：恢复锚点，与检查点编号无关。 */
    private static String pauseIdOf(RunArtifactEventVO artifact) {
        @SuppressWarnings("unchecked")
        Map<String, Object> content = MAPPER.convertValue(artifact.content(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) content.get("data");
        return (String) data.get("pauseId");
    }

    /** 需求产物事件载荷：先于 run_finished 到达。 */
    private static RunArtifactEventVO needsArtifactEvent(List<ServerSentEvent<Object>> frames) {
        ServerSentEvent<Object> frame = frames.stream()
                .filter(sse -> RunProgressPublisher.EVENT_ARTIFACT.equals(sse.event()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未收到 artifact 事件"));
        return MAPPER.convertValue(frame.data(), RunArtifactEventVO.class);
    }

    private static int eventIndex(List<ServerSentEvent<Object>> frames, String event) {
        return IntStream.range(0, frames.size())
                .filter(i -> event.equals(frames.get(i).event()))
                .findFirst()
                .orElse(-1);
    }

    /** 恢复失败以 run_stopped 帧返回业务文案，不再向调用方抛异常。 */
    private String resumeStreamError(GraphServiceImpl service, GraphResumeRequest request) {
        ServerSentEvent<Object> terminal = terminal(collect(service.streamResume(request)));
        assertThat(terminal.event()).isEqualTo(RunProgressPublisher.EVENT_RUN_STOPPED);
        RunStoppedEventVO stopped = MAPPER.convertValue(terminal.data(), RunStoppedEventVO.class);
        assertThat(stopped.status()).isEqualTo("FAILED");
        return stopped.reason();
    }

    /** 线程标识按生产规则拼装；测试登记后由清理钩子删除。 */
    private String threadOf(long userId) {
        String thread = new ConversationThreads().of(userId, TEST_CONVERSATION_ID);
        ownedThreads.add(thread);
        return thread;
    }

    /** 数据范围节点用桩：拓扑测试不关心元数据组装。 */
    private DataAccessNode dataAccess() {
        return new DataAccessNode(new StubSchemaProvider());
    }

    private CompiledGraph graph(ScriptedModel model, int maxRetries) throws Exception {
        RedisSaver saver = new RedisSaverConfiguration().graphRedisSaver(redis);
        // 记忆写入统一走 Recorder，检查点集成测试用 mock 断言节点装配即可
        nodeMemoryRecorder = mock(NodeMemoryRecorder.class);
        PlannerNode planner = new PlannerNode(ChatClient.create(model), plannerPrompt, CLOCK,
                nodeMemoryRecorder);
        NeedCheckNode needCheck = new NeedCheckNode(MAPPER, validators.getValidator(), maxRetries, 3, 6,
                nodeMemoryRecorder, publishedStore(), new ConversationThreads(), new VisibleResultFactory());
        NeedReviewNode needReview = new NeedReviewNode(ChatClient.create(model), reviewPrompt, MAPPER, maxRetries,
                3, nodeMemoryRecorder, publishedStore(), new ConversationThreads(), new VisibleResultFactory(),
                CLOCK);
        NeedSelectorNode needSelector = new NeedSelectorNode(MAPPER);
        KnowledgeNode knowledge = new KnowledgeNode(new AuthorizedKnowledgeProvider());
        return GraphTopology.compile(planner, needCheck, needReview, needSelector, dataAccess(), knowledge,
                saver, maxRetries);
    }

    /** 发布拍消息库：会话活跃时间固定成功，发布拍 append 全程可成功，方便断言内容事件。 */
    private ConversationMessageStore publishedStore() {
        AiConversationMapper conversations = mock(AiConversationMapper.class);
        when(conversations.touchLastMessageAt(anyLong(), any())).thenReturn(1);
        return new ConversationMessageStore(mock(AiMessageMapper.class), conversations,
                new VisibleMessageCodec(MAPPER), stubTransactions());
    }

    private TransactionTemplate stubTransactions() {
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new TransactionTemplate(tx);
    }

    private GraphServiceImpl service(CompiledGraph graph) {
        @SuppressWarnings("unchecked")
        ObjectProvider<CompiledGraph> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(graph);
        AiConversationMapper conversations = mock(AiConversationMapper.class);
        when(conversations.selectOwned(anyLong(), anyLong()))
                .thenReturn(new AiConversation().setUserId(1L).setTitle("集成测试会话"));
        when(conversations.touchLastMessageAt(anyLong(), any())).thenReturn(1);
        TransactionTemplate transactions = stubTransactions();
        VisibleMessageCodec codec = new VisibleMessageCodec(MAPPER);
        ConversationMessageStore store =
                new ConversationMessageStore(mock(AiMessageMapper.class), conversations, codec, transactions);
        ConversationThreads threads = new ConversationThreads();
        RunStartPreparer startPreparer =
                new RunStartPreparer(provider, conversations, store, threads, codec);
        RunResumePreparer resumePreparer = new RunResumePreparer(provider, conversations, store, threads,
                nodeMemoryRecorder);
        RunFailureHandler failureHandler = new RunFailureHandler(store);
        RunProgressPublisher publisher = new RunProgressPublisher();
        RunCoordinator coordinator = new RunCoordinator(new ConversationExecutionLock(redis), publisher,
                conversations, threads, startPreparer, resumePreparer, failureHandler, store);
        RunStateResolver stateResolver = new RunStateResolver(publisher, store, provider, codec, threads);
        return new GraphServiceImpl(provider, coordinator, stateResolver);
    }

    private static RunnableConfig threadConfig(String threadId) {
        return RunnableConfig.builder().threadId(threadId).build();
    }

    private static GraphResumeRequest resume(String threadId, String pauseId, String answer) {
        return new GraphResumeRequest(threadId, pauseId, answer);
    }

    private static MockedStatic<UserContext> login(Long userId) {
        LoginUser user = new LoginUser();
        user.setUserId(userId);
        MockedStatic<UserContext> users = mockStatic(UserContext.class);
        users.when(UserContext::requireCurrentUser).thenReturn(user);
        return users;
    }

    private static String clarification(String question) throws Exception {
        return MAPPER.writeValueAsString(Map.of(
                "needs", List.of(),
                "needs_clarification", true,
                "questions", List.of(Map.of("id", "scope", "question", question, "options", List.of()))));
    }

    private static String readyNeeds() {
        return """
                {
                  "needs": [
                    {
                      "id": "N1",
                      "intent": "DATA",
                      "need": "统计指定月份和仓库的商品销量",
                      "depends": [],
                      "condition": null,
                      "evidence_plan": [
                        {"id": "N1-E1", "label": "商品销量", "instruction": "按指定月份和仓库汇总商品销量",
                         "source": "SQL", "depends": []}
                      ]
                    }
                  ],
                  "needs_clarification": false,
                  "questions": []
                }
                """;
    }

    /**
     * 审阅回流后的重写产出：同一个需求 id，交回**完整**证据清单。
     *
     * <p>已有需求重写时要保留的旧项必须原样带上，不交回即删除。</p>
     */
    private static String rewrittenWithInTransit() {
        return """
                {
                  "needs": [
                    {
                      "id": "N1",
                      "intent": "DATA",
                      "need": "统计指定月份和仓库的商品销量",
                      "depends": [],
                      "condition": null,
                      "evidence_plan": [
                        {"id": "N1-E1", "label": "商品销量", "instruction": "按指定月份和仓库汇总商品销量",
                         "source": "SQL", "depends": []},
                        {"id": "N1-E2", "label": "在途采购量", "instruction": "查未入库的采购单量",
                         "source": "SQL", "depends": []}
                      ]
                    }
                  ],
                  "needs_clarification": false,
                  "questions": []
                }
                """;
    }

    private static String reviewOk() {
        return "{\"verdict\": \"OK\", \"issues\": []}";
    }

    private static String reviewIssues() {
        return """
                {"verdict": "ISSUES", "issues": [
                  {"need_id": "N1", "problem": "漏查在途采购量", "suggestion": "追加证据项 N1-E2"}
                ]}
                """;
    }

    /**
     * 脚本模型：按调用顺序返回预设输出。
     *
     * <p>planner 与 need_review 共用一个模型实例，脚本按顺序消费即可；
     * 审阅节点与规划节点的调用次数在正常路径上各占一次。</p>
     */
    private static final class ScriptedModel implements ChatModel {

        private final List<String> outputs;
        private final AtomicInteger invocation = new AtomicInteger();
        private final List<String> prompts = new CopyOnWriteArrayList<>();

        private ScriptedModel(String... outputs) {
            this.outputs = List.of(outputs);
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getContents());
            int index = invocation.getAndIncrement();
            if (index >= outputs.size()) {
                throw new AssertionError("模型发起了超出脚本的调用，第" + (index + 1) + "次");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(outputs.get(index)))));
        }

        private int calls() {
            return invocation.get();
        }
    }
}
