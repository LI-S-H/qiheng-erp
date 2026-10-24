package com.qiheng.erp.ai.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.constant.AiMessageKind;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.constant.NeedReviewResult;
import com.qiheng.erp.ai.constant.NeedStatus;
import com.qiheng.erp.ai.domain.dto.needs.Need;
import com.qiheng.erp.ai.domain.dto.needs.NeedIntent;
import com.qiheng.erp.ai.domain.dto.needs.NeedReviewIssue;
import com.qiheng.erp.ai.domain.dto.needs.NeedReviewOutput;
import com.qiheng.erp.ai.domain.dto.needs.NeedReviewVerdict;
import com.qiheng.erp.ai.domain.vo.VisibleMessageContent;
import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.qiheng.erp.ai.graph.support.JsonPayload;
import com.qiheng.erp.ai.graph.support.NeedStore;
import com.qiheng.erp.ai.graph.support.NodeMemoryRecorder;
import com.qiheng.erp.ai.graph.support.TimeScope;
import com.qiheng.erp.ai.service.VisibleResultFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务完整性审阅节点：审"拆得对不对"，不审"数据对不对"。
 *
 * <p>结构合法性由 {@code NeedCheckNode} 在代码里判；代码判不了"漏查了在途采购量"
 * 这类业务遗漏，这类问题由本节点用模型审。两者分开，谁也不越权。</p>
 *
 * <p>审阅范围只有 {@code DATA} 需求。{@code ADVICE} 的 {@code PROPOSAL} 项只是"要什么方案 +
 * 注意事项"的交接说明，方案要查什么、算得对不对由方案子图自己规划与验证，本节点不审；
 * 方案子图展开出的 D 段需求同样由子图自审。</p>
 *
 * <p>审出问题回 planner 修复；
 * 本节点自身的输出格式不合规则回本节点重问一次，不必惊动 planner 重拆一遍。</p>
 *
 * <p>审阅通过即消费完成：两份需求草案（planner 的与方案子图的）一起清空。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
@Slf4j
public class NeedReviewNode implements NodeAction {

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ARTIFACT_VERSION = "1";

    private final ChatClient chatClient;
    private final String systemPrompt;
    private final ObjectMapper mapper;
    private final int maxFormatRetries;
    private final int maxReviewRounds;
    private final NodeMemoryRecorder nodeMemoryRecorder;
    private final ConversationMessageStore messageStore;
    private final ConversationThreads threads;
    private final VisibleResultFactory visibleResults;
    private final Clock clock;
    private final BeanOutputConverter<NeedReviewOutput> converter =
            new BeanOutputConverter<>(NeedReviewOutput.class);

    public NeedReviewNode(ChatClient chatClient, String systemPrompt, ObjectMapper mapper,
                          int maxFormatRetries, int maxReviewRounds, NodeMemoryRecorder nodeMemoryRecorder,
                          ConversationMessageStore messageStore, ConversationThreads threads,
                          VisibleResultFactory visibleResults, Clock clock) {
        this.chatClient = chatClient;
        this.systemPrompt = systemPrompt;
        this.mapper = mapper.copy()
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.maxFormatRetries = maxFormatRetries;
        this.maxReviewRounds = maxReviewRounds;
        this.nodeMemoryRecorder = nodeMemoryRecorder;
        this.messageStore = messageStore;
        this.threads = threads;
        this.visibleResults = visibleResults;
        this.clock = clock;
    }

    /**
     * 节点入口：取待审需求 → 调模型审阅 → 按结论分流。
     *
     * <p>分流规则：
     * <ul>
     *   <li>无待审需求 → 直接放行（{@code OK}），不浪费模型调用</li>
     *   <li>待审需求全是 {@code ADVICE} → 不调模型，直接定稿发布（方案由子图自审）</li>
     *   <li>模型输出解析失败 → {@link #formatFailure} 回本节点重问</li>
     *   <li>verdict=ISSUES → {@link #backToPlanner} 让 planner 按问题修复</li>
     *   <li>verdict=OK → {@link #accept} 定稿并发布</li>
     * </ul>
     * </p>
     */
    @Override
    public Map<String, Object> apply(OverAllState state) {
        // ← 取待审需求
        List<NeedStore.StoredNeed> pending = pendingNeeds(state);
        if (pending.isEmpty()) {
            // 没有待审需求时直接放行，避免空审一轮浪费模型调用。
            return Map.of(GraphStateKeys.NEED_REVIEW_RESULT, NeedReviewResult.OK.name());
        }
        // ADVICE 不审：它的 PROPOSAL 项只是交接说明，方案质量由方案子图自审；
        // 这里只保留消费与发布，避免对"方案要查什么"提要求造成来回改。
        List<NeedStore.StoredNeed> reviewable = pending.stream()
                .filter(stored -> stored.need().intent() == NeedIntent.DATA)
                .toList();
        // 方案子图产出的 D 段需求也由子图自审：本轮草案来自 PROPOSAL_OUTPUT 时直接定稿。
        boolean fromProposal = !String.valueOf(
                state.value(GraphStateKeys.PROPOSAL_OUTPUT, "")).isBlank();
        if (reviewable.isEmpty() || fromProposal) {
            return accept(state, pending);
        }

        // system 段每次现拼：静态规则 + 业务知识（按权限装配，写进 State）+ 输出格式
        String output = chatClient.prompt()
                .system(buildSystemPrompt(state))
                .user(reviewInput(state, reviewable, pending))
                .call()
                .content();
        if (output == null) {
            output = "";
        }

        // → 解析模型输出
        NeedReviewOutput review;
        try {
            review = mapper.readValue(JsonPayload.unwrap(output), NeedReviewOutput.class);
        } catch (JsonProcessingException error) {
            // 模型偶尔只给一个 issues 数组、漏掉 verdict 外壳；语义是明确的，直接按数组解读，
            // 不必为外壳再烧一轮格式修复。
            review = asIssuesArray(output);
            if (review == null) {
                // 解析失败只回本节点修格式，不回 planner——业务判断还没出来，没必要重拆。
                return formatFailure(state, output, error);
            }
        }

        // → 按结论分流
        if (review.verdict() == NeedReviewVerdict.ISSUES) {
            // ISSUES 必须带问题清单；空 issues 算协议错误，走格式修复。
            if (review.issues().isEmpty()) {
                return formatFailure(state, output, new IllegalArgumentException(
                        "verdict=ISSUES 时 issues 必须非空"));
            }
            // ← 有问题，回 planner 修问题
            return backToPlanner(state, review);
        }
        // 通过，定稿
        return accept(state, pending);
    }

    /**
     * 取待审需求：只审尚未进入终态的需求。
     *
     * <p>已终态的需求是上一轮定下来的结论，改它要靠新的派生需求，不是审阅能做的事。</p>
     */
    private List<NeedStore.StoredNeed> pendingNeeds(OverAllState state) {
        List<NeedStore.StoredNeed> pending = new ArrayList<>();
        for (NeedStore.StoredNeed stored : NeedStore.read(state, mapper)) {
            if (stored.status() == NeedStatus.PENDING) {
                pending.add(stored);
            }
        }
        return pending;
    }

    /**
     * 兜底解析：模型只给 issues 数组、漏掉 verdict 外壳时，按数组语义解读。
     *
     * @param output 模型原始输出
     * @return 审阅结论；不是合法的 issues 数组时返回 null
     */
    private NeedReviewOutput asIssuesArray(String output) {
        String json = output.trim();
        if (!json.startsWith("[")) {
            return null;
        }
        try {
            List<NeedReviewIssue> issues = mapper.readValue(json, new TypeReference<>() {
            });
            for (NeedReviewIssue issue : issues) {
                if (issue.needId() == null || issue.problem() == null || issue.suggestion() == null) {
                    return null;
                }
            }
            return new NeedReviewOutput(
                    issues.isEmpty() ? NeedReviewVerdict.OK : NeedReviewVerdict.ISSUES, issues);
        } catch (JsonProcessingException error) {
            return null;
        }
    }

    /**
     * 拼 system 段：静态规则 + 业务知识 + 输出格式。
     *
     * <p>业务知识来自 {@code KnowledgeNode} 写进 State 的那一份（与 planner 同源）。
     * 它在一轮里恒定，放 system 段前缀稳定、对缓存友好。</p>
     */
    private String buildSystemPrompt(OverAllState state) {
        StringBuilder system = new StringBuilder(systemPrompt);
        String knowledge = String.valueOf(state.value(GraphStateKeys.BUSINESS_KNOWLEDGE, "")).trim();
        if (!knowledge.isBlank()) {
            system.append("\n\n## 业务知识\n").append(knowledge);
        }
        return system.append("\n\n").append(converter.getFormat()).toString();
    }

    /**
     * 已完成需求的摘要：只留核对引用需要的字段，不带 instruction。
     */
    private String terminalSummary(OverAllState state) {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (NeedStore.StoredNeed stored : NeedStore.read(state, mapper)) {
            if (stored.status() == NeedStatus.PENDING) {
                continue;
            }
            Need need = stored.need();
            summaries.add(Map.of(
                    "id", need.id(),
                    "need", need.need(),
                    "status", stored.status().name(),
                    "depends", need.depends(),
                    "condition", need.condition() == null ? "" : need.condition().left(),
                    "evidence", need.evidencePlan().stream()
                            .map(item -> Map.of("id", item.id(), "label", item.label(),
                                    "source", item.source().name(), "depends", item.depends()))
                            .toList()));
        }
        return summaries.isEmpty() ? "" : mapper.convertValue(summaries, List.class).toString();
    }
    /**
     * 拼装审阅输入：原始需求、待审需求、已完成需求摘要、表结构。
     *
     * <p>不喂执行结果与明细数据——审的是计划查得对不对，不是查出来的数对不对。</p>
     *
     * <p>已完成需求只给摘要（不带 instruction）：新需求可能引用它们的证据项，
     * 不给就判不出引用合不合理。</p>
     *
     * <p>ADVICE 只作为上下文出现（不进待审列表）：不带上的话，review 看不见"方案已经请求过了"，
     * 会每轮都要求补一条 ADVICE。</p>
     */
    private String reviewInput(OverAllState state, List<NeedStore.StoredNeed> pending,
                              List<NeedStore.StoredNeed> allPending) {
        StringBuilder input = new StringBuilder();
        input.append("时间口径：").append(TimeScope.text(OffsetDateTime.now(clock))).append('\n');
        input.append("原始需求：\n").append(state.value(GraphStateKeys.USER_REQUEST, "")).append('\n');
        input.append("\n待审需求数组：\n")
                .append(mapper.convertValue(NeedStore.toMaps(pending, mapper), List.class)).append('\n');
        List<NeedStore.StoredNeed> advice = allPending.stream()
                .filter(stored -> stored.need().intent() == NeedIntent.ADVICE)
                .toList();
        if (!advice.isEmpty()) {
            input.append("\n同一轮已拆出的 ADVICE（不审；它已经覆盖了用户的方案请求，不要再要求补 ADVICE）：\n")
                    .append(mapper.convertValue(NeedStore.toMaps(advice, mapper), List.class)).append('\n');
        }
        String terminal = terminalSummary(state);
        if (!terminal.isBlank()) {
            input.append("\n已完成需求（摘要，仅用于核对引用）：\n").append(terminal).append('\n');
        }
        // 上一轮自己提的问题：避免同一件事重复报，也避免两轮之间来回改口
        List<?> previousIssues = state.value(GraphStateKeys.REPAIR_ISSUES, List.of());
        if (!previousIssues.isEmpty()) {
            input.append("\n上一轮你提的问题（planner 已按它改过；这轮不要重复报，撤回或改口要说明理由）：\n")
                    .append(previousIssues).append('\n');
        }
        Map<String, Object> schemaRef = state.value(GraphStateKeys.AUTHORIZED_SCHEMA_REF, Map.of());
        input.append("\n当前可见数据表结构：\n")
                .append(schemaRef.getOrDefault("schema", "")).append('\n');

        Map<String, Object> error = state.value(GraphStateKeys.NEED_REVIEW_ERROR, Map.of());
        if (!error.isEmpty()) {
            // 格式修复模式：把上次的错误输出和错误明细喂回去
            input.append("\n本次为审阅输出格式修复。上一份未通过解析的完整输出：\n")
                    .append(state.value(GraphStateKeys.NEED_REVIEW_OUTPUT, "")).append('\n');
            input.append("具体错误：\n").append(error).append('\n');
            input.append("保持审阅判断不变，只修正输出格式，重新输出完整 JSON。\n");
        }
        return input.toString();
    }

    /**
     * 审出问题：回 planner 修复，问题原文作为反馈带回去。
     *
     * <p>业务漏查的回流单独计数（{@code review_count}）：一次修不完是常态，
     * 真实模型实测要两轮才能把"明细表字段写错""漏了草稿过滤""重复扣减"补齐。
     * 与格式修复共用一份预算会把合法的多轮修复误判成死循环。</p>
     */
    private Map<String, Object> backToPlanner(OverAllState state, NeedReviewOutput review) {
        // 把模型返回的问题转成 Map 结构，写回 State 供 planner 读取。
        List<Map<String, Object>> issues = new ArrayList<>();
        for (NeedReviewIssue issue : review.issues()) {
            issues.add(Map.of(
                    "need_id", issue.needId(),
                    "problem", issue.problem(),
                    "suggestion", issue.suggestion()));
        }
        Map<String, Object> failure = Map.of(
                "type", "NeedReviewIssues",
                "message", "需求拆解存在业务遗漏，请按建议补齐后重新输出完整需求数组");
        int reviewCount = state.value(GraphStateKeys.REVIEW_COUNT, 0);
        Map<String, Object> update = new HashMap<>();
        update.put(GraphStateKeys.REPAIR_ERROR, failure);
        update.put(GraphStateKeys.REPAIR_ISSUES, issues);
        if (reviewCount >= maxReviewRounds) {
            // 审阅预算耗尽：不再回 planner，直接收场。
            update.put(GraphStateKeys.REVIEW_COUNT, reviewCount);
            update.put(GraphStateKeys.NEED_REVIEW_RESULT, NeedReviewResult.FORMAT_RETRY_EXHAUSTED.name());
            ConversationIdentity identity = identity(state);
            if (identity != null) {
                // 发布错误结果传回前端，提示用户格式修复耗尽
                persistIncompleteQuietly(identity);
                // 记录格式修复耗尽事件
                nodeMemoryRecorder.formatExhausted(identity.conversationId(), identity.userId(),
                        identity.requestId());
            }
            return update;
        }
        // 预算够用：审阅轮次 +1，标记 ISSUES 让图回流到 planner。
        update.put(GraphStateKeys.REVIEW_COUNT, reviewCount + 1);
        update.put(GraphStateKeys.NEED_REVIEW_RESULT, NeedReviewResult.ISSUES.name());
        recordIssues(state, issues);
        return update;
    }

    /**
     * 审阅通过：发布已接受的需求数组，并把修复预算归零。
     */
    private Map<String, Object> accept(OverAllState state, List<NeedStore.StoredNeed> pending) {
        Map<String, Object> update = new HashMap<>();
        update.put(GraphStateKeys.NEED_REVIEW_RESULT, NeedReviewResult.OK.name());
        // 审阅通过，把两类修复预算都归零，为后续执行阶段腾出干净状态。
        update.put(GraphStateKeys.REPAIR_COUNT, 0);
        update.put(GraphStateKeys.REVIEW_COUNT, 0);
        update.put(GraphStateKeys.REPAIR_ERROR, Map.of());
        update.put(GraphStateKeys.REPAIR_ISSUES, List.of());
        update.put(GraphStateKeys.NEED_REVIEW_ERROR, Map.of());
        update.put(GraphStateKeys.NEED_REVIEW_OUTPUT, "");
        // 需求草案消费完成：两份一起清空。下一轮谁再产草案都从头开始，
        // 也不会有陈旧草案被下一次校验误读。
        update.put(GraphStateKeys.PLANNER_OUTPUT, "");
        update.put(GraphStateKeys.PROPOSAL_OUTPUT, "");
        publishNeeds(state, pending, update);
        return update;
    }

    /**
     * 发布拍：需求数组定稿后落库并写入内容投影。
     *
     * <p>放在审阅通过之后而不是结构校验之后：结构通过但业务不完整时需求还会被改写，
     * 提前发布会把没定稿的需求推给前端。</p>
     */
    private void publishNeeds(OverAllState state, List<NeedStore.StoredNeed> pending,
                              Map<String, Object> update) {
        ConversationIdentity identity = identity(state);
        if (identity == null) {
            return;
        }
        try {
            VisibleMessageContent content = visibleResults.acceptedNeeds(
                    NeedStore.toMaps(pending, mapper),
                    threads.of(identity.userId(), identity.conversationId()));
            update.put(GraphStateKeys.PUBLISHED_ARTIFACT, artifactOf(content, identity.requestId()));
            persistNeedsQuietly(identity, content);
            nodeMemoryRecorder.acceptedNeeds(identity.conversationId(), identity.userId(),
                    identity.requestId(), "NEEDS_READY");
        } catch (Exception exception) {
            log.warn("需求产物投影失败 conversationId 状态缺失或序列化异常", exception);
        }
    }

    /**
     * 落库一条"需求已定稿"的最终消息；已有 final 则跳过，失败只记日志。
     */
    private void persistNeedsQuietly(ConversationIdentity identity, VisibleMessageContent content) {
        try {
            if (messageStore.findFinal(identity.conversationId(), identity.userId(), identity.requestId()) != null) {
                return;
            }
            messageStore.append(identity.conversationId(), identity.userId(), identity.requestId(),
                    ROLE_ASSISTANT, AiMessageKind.ASSISTANT_FINAL, null, null, content);
        } catch (Exception exception) {
            log.warn("需求消息发布拍保存失败", exception);
        }
    }

    /**
     * 审阅输出格式不合规：回本节点重问一次，预算耗尽则收场。
     */
    private Map<String, Object> formatFailure(OverAllState state, String output,
                                              Exception error) {
        // 保留原始输出与错误明细，下一轮 reviewInput 会把它们拼进提示词，
        // 让模型只修格式、不改业务判断。
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", error.getClass().getSimpleName());
        detail.put("message", error.getMessage());
        int retryCount = state.value(GraphStateKeys.REPAIR_COUNT, 0);
        Map<String, Object> update = new HashMap<>();
        update.put(GraphStateKeys.NEED_REVIEW_ERROR, detail);
        update.put(GraphStateKeys.NEED_REVIEW_OUTPUT, output);
        if (retryCount < maxFormatRetries) {
            // 格式修复预算够用：回本节点重问一次，不回 planner。
            update.put(GraphStateKeys.REPAIR_COUNT, retryCount + 1);
            update.put(GraphStateKeys.NEED_REVIEW_RESULT, NeedReviewResult.RETRY_FORMAT.name());
        } else {
            // 格式修复预算耗尽：收场，提示用户本轮拆解未完成。
            update.put(GraphStateKeys.REPAIR_COUNT, retryCount);
            update.put(GraphStateKeys.NEED_REVIEW_RESULT, NeedReviewResult.FORMAT_RETRY_EXHAUSTED.name());
            ConversationIdentity identity = identity(state);
            if (identity != null) {
                persistIncompleteQuietly(identity);
                nodeMemoryRecorder.formatExhausted(identity.conversationId(), identity.userId(),
                        identity.requestId());
            }
        }
        return update;
    }

    /**
     * 落库一条"计划未完成"的最终消息；已有 final 则跳过，失败只记日志。
     *
     * <p>用于审阅预算耗尽时的收场，提示用户本轮拆解未能完成。</p>
     */
    private void persistIncompleteQuietly(ConversationIdentity identity) {
        try {
            if (messageStore.findFinal(identity.conversationId(), identity.userId(), identity.requestId()) != null) {
                return;
            }
            messageStore.append(identity.conversationId(), identity.userId(), identity.requestId(),
                    ROLE_ASSISTANT, AiMessageKind.ASSISTANT_FINAL, null, null,
                    visibleResults.planIncomplete(threads.of(identity.userId(), identity.conversationId())));
        } catch (Exception exception) {
            log.warn("未完成提示发布拍保存失败", exception);
        }
    }

    /**
     * 把审出的问题写进节点记忆，planner 下一轮能看到并据此修复。
     */
    private void recordIssues(OverAllState state, List<Map<String, Object>> issues) {
        ConversationIdentity identity = identity(state);
        if (identity != null) {
            nodeMemoryRecorder.reviewIssues(identity.conversationId(), identity.userId(),
                    identity.requestId(), issues);
        }
    }

    /**
     * 把可见消息内容包装成统一的 artifact 结构，写回 State 供前端消费。
     */
    private Map<String, Object> artifactOf(VisibleMessageContent content, String requestId) {
        Map<String, Object> artifact = new HashMap<>();
        artifact.put("type", content.type());
        artifact.put("version", ARTIFACT_VERSION);
        artifact.put("requestId", requestId);
        artifact.put("content", mapper.convertValue(content, Map.class));
        return artifact;
    }

    /**
     * 解析 State 里的会话归属；缺失或非法时返回 null，调用方据此跳过落库与记忆写入。
     */
    private ConversationIdentity identity(OverAllState state) {
        try {
            Object conversationIdObj = state.value(GraphStateKeys.CONVERSATION_ID).orElse(null);
            Object userIdObj = state.value(GraphStateKeys.USER_ID).orElse(null);
            if (conversationIdObj == null || userIdObj == null) {
                return null;
            }
            return new ConversationIdentity(Long.parseLong(conversationIdObj.toString()),
                    Long.parseLong(userIdObj.toString()), state.value(GraphStateKeys.REQUEST_ID, ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private record ConversationIdentity(long conversationId, long userId, String requestId) {
    }
}
