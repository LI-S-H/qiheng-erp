package com.qiheng.erp.ai.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.constant.AiMessageKind;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.constant.NeedCheckResult;
import com.qiheng.erp.ai.constant.NeedStatus;
import com.qiheng.erp.ai.domain.dto.needs.ClarificationQuestion;
import com.qiheng.erp.ai.domain.dto.needs.EvidenceItem;
import com.qiheng.erp.ai.domain.dto.needs.EvidenceSource;
import com.qiheng.erp.ai.domain.dto.needs.Need;
import com.qiheng.erp.ai.domain.dto.needs.NeedIntent;
import com.qiheng.erp.ai.domain.dto.needs.PlannerOutput;
import com.qiheng.erp.ai.domain.vo.VisibleMessageContent;
import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.qiheng.erp.ai.graph.support.JsonPayload;
import com.qiheng.erp.ai.graph.support.NeedDependencies;
import com.qiheng.erp.ai.graph.support.NeedStore;
import com.qiheng.erp.ai.graph.support.NeedStore.StoredNeed;
import com.qiheng.erp.ai.graph.support.NodeMemoryRecorder;
import com.qiheng.erp.ai.memory.node.WorkingMemoryTranscript;
import com.qiheng.erp.ai.service.VisibleResultFactory;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 需求结构校验节点：只看结构，不看业务（业务完整性由 {@code NeedReviewNode} 审）。
 *
 * <p>校验通过后把本轮产出并进 State 的需求数组。合并规则：需求 id 没出现过就是新需求；
 * 已存在就<b>整条替换它的计划</b>——证据清单、{@code depends}、{@code condition} 一起换，
 * 正文与 {@code intent} 保持定稿值。不交回的旧证据项就是删除，换口径要用新 id。</p>
 *
 * <p>校验一次收集全部问题再回给 planner，不是遇到第一条就返回。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
@Slf4j
public class NeedCheckNode implements NodeAction {

    private static final String ROLE_ASSISTANT = "assistant";
    private static final String ARTIFACT_VERSION = "1";

    /** 条件开关里证据项引用的前缀，形如 {@code evidence.N1-E5}。 */
    private static final String EVIDENCE_PREFIX = "evidence.";

    /** 一次最多回给 planner 几条问题：给多了改不完，给一条又得多跑几轮。 */
    private static final int MAX_REPORTED_PROBLEMS = 10;

    /** 需求 id 段：用户需求。 */
    private static final String USER_NEED_PREFIX = "N";

    /** 需求 id 段：派生需求。 */
    private static final String DERIVED_NEED_PREFIX = "D";

    private final ObjectMapper mapper;
    private final Validator validator;
    private final int maxFormatRetries;
    private final int maxUserNeeds;
    private final int maxDerivedNeeds;
    private final NodeMemoryRecorder nodeMemoryRecorder;
    private final ConversationMessageStore messageStore;
    private final ConversationThreads threads;
    private final VisibleResultFactory visibleResults;

    /**
     * @param maxFormatRetries 格式修复重试上限
     * @param maxUserNeeds     同时未完成的用户需求上限
     * @param maxDerivedNeeds  同时未完成的派生需求上限
     */
    public NeedCheckNode(ObjectMapper mapper, Validator validator, int maxFormatRetries, int maxUserNeeds,
                         int maxDerivedNeeds, NodeMemoryRecorder nodeMemoryRecorder,
                         ConversationMessageStore messageStore, ConversationThreads threads,
                         VisibleResultFactory visibleResults) {
        if (maxFormatRetries < 0 || maxUserNeeds < 1 || maxDerivedNeeds < 0) {
            throw new IllegalArgumentException("修复次数不能为负，用户需求上限必须为正数");
        }
        // 不接受字符串布尔值、浮点序号或 JSON 后的多余内容，避免悄悄修正错误协议。
        this.mapper = mapper.copy()
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.validator = validator;
        this.maxFormatRetries = maxFormatRetries;
        this.maxUserNeeds = maxUserNeeds;
        this.maxDerivedNeeds = maxDerivedNeeds;
        this.nodeMemoryRecorder = nodeMemoryRecorder;
        this.messageStore = messageStore;
        this.threads = threads;
        this.visibleResults = visibleResults;
    }

    /**
     * 节点入口：解析 → 澄清分支 → 合并 → 结构校验 → 配额 → 就绪。
     *
     * <p>先合并再校验：重写后的清单可以引用其它需求已列出的证据项，依赖、顺序与配额
     * 都要在合并后的完整清单上判。</p>
     */
    @Override
    public Map<String, Object> apply(OverAllState state) {
        PlannerOutput output;
        try {
            // 剥掉模型可能回显的 [输入]/[输出]/[摘要] 标记，否则会被当成 JSON 语法错误
            String raw = JsonPayload.unwrap(WorkingMemoryTranscript.strip(draftOf(state)));
            output = mapper.readValue(raw, PlannerOutput.class);
        } catch (JsonProcessingException error) {
            // 解析失败保留结构化明细（类型/位置/字段路径），planner 才能按位置修
            return formatFailure(state, describeParseError(error));
        }
        if (output == null) {
            return fail(state, List.of("输出必须是需求数组对象，不能为 null"));
        }
        // 2. 澄清分支：planner 说信息不足，要问用户确认
        if (Boolean.TRUE.equals(output.needsClarification())) {
            List<String> problems = checkClarification(output);
            // 校验通过后发布澄清问题，否则返回错误明细
            return problems.isEmpty() ? publishClarification(state, output) : fail(state, problems);
        }
        if (!output.questions().isEmpty()) {
            return fail(state, List.of("needs_clarification=false 时 questions 必须为空"));
        }
        // 3. 合并分支：新 id 收录，已有 id 整条替换它的计划
        MergeOutcome outcome = merge(output.needs(), NeedStore.read(state, mapper));
        if (!outcome.violations().isEmpty()) {
            // 合并有违规就 fail
            return fail(state, outcome.violations());
        }
        // 4. 结构校验分支：合并后校验需求结构是否符合要求
        List<String> problems = new ArrayList<>(checkStructure(outcome.merged(), state));
        problems.addAll(checkQuota(outcome.merged()));
        if (!problems.isEmpty()) {
            return fail(state, problems);
        }
        // 5. 全部通过，把合并后的需求写回 state，标记 READY，并清空修复计数和错误
        Map<String, Object> update = new HashMap<>();
        update.put(GraphStateKeys.NEEDS, NeedStore.toMaps(outcome.merged(), mapper));
        update.put(GraphStateKeys.REPAIR_COUNT, 0);
        update.put(GraphStateKeys.REPAIR_ERROR, Map.of());
        update.put(GraphStateKeys.NEED_CHECK_RESULT, NeedCheckResult.READY.name());
        return update;
    }

    /**
     * 取本轮的需求草案。
     *
     * <p>草案有两个来源：planner 产的与方案子图产的。两者串行产生，**子图那份优先**；
     * 两份都有值时以子图为准，另一份是陈旧草案，审阅通过后会一起清掉。</p>
     *
     * <p>哪份草案键有值就是谁产的——失败回流据此回谁（§10.1.4）。</p>
     */
    private String draftOf(OverAllState state) {
        String proposalDraft = state.value(GraphStateKeys.PROPOSAL_OUTPUT, "");
        if (proposalDraft != null && !proposalDraft.isBlank()) {
            return proposalDraft;
        }
        String plannerDraft = state.value(GraphStateKeys.PLANNER_OUTPUT, "");
        return plannerDraft == null ? "" : plannerDraft;
    }

    /** 澄清分支的协议校验：问题非空、需求为空、问题 id 唯一、候选项不重复。 */
    private List<String> checkClarification(PlannerOutput output) {
        if (output.questions().isEmpty() || !output.needs().isEmpty()) {
            return List.of("needs_clarification=true 时 questions 必须非空，needs 必须为空");
        }
        List<String> problems = new ArrayList<>();
        Set<String> questionIds = new HashSet<>();
        for (int i = 0; i < output.questions().size(); i++) {
            ClarificationQuestion question = output.questions().get(i);
            if (!questionIds.add(question.id().trim())) {
                problems.add("questions[" + i + "].id 在本轮重复");
            }
            Set<String> options = new HashSet<>();
            for (String option : question.options()) {
                if (!options.add(option.trim())) {
                    problems.add("questions[" + i + "].options 存在重复候选项「" + option + "」");
                }
            }
        }
        return problems;
    }

    /**
     * 澄清发布拍：落库澄清消息并写内容投影，供进度追踪器推送 clarification_required 事件。
     *
     * <p>顺带重置修复预算：用户补充信息后值得一次完整的拆解机会。</p>
     */
    private Map<String, Object> publishClarification(OverAllState state, PlannerOutput output) {
        // 发布澄清问题，更新状态为 WAITING_HUMAN 并重置修复预算和审核计数
        String pauseId = UUID.randomUUID().toString();
        Map<String, Object> update = new HashMap<>();
        update.put(GraphStateKeys.PAUSE_ID, pauseId);
        update.put(GraphStateKeys.CLARIFICATION_QUESTIONS, output.questions());
        update.put(GraphStateKeys.NEED_CHECK_RESULT, NeedCheckResult.WAITING_HUMAN.name());
        update.put(GraphStateKeys.REPAIR_COUNT, 0);
        update.put(GraphStateKeys.REVIEW_COUNT, 0);
        update.put(GraphStateKeys.REPAIR_ERROR, Map.of());
        // 校验会话是否归属当前用户
        ConversationIdentity identity = identity(state);
        if (identity == null) {
            return update;
        }
        try {
            VisibleMessageContent content = visibleResults.clarification(output,
                    threads.of(identity.userId(), identity.conversationId()), pauseId);
            Map<String, Object> artifact = new HashMap<>();
            artifact.put("type", content.type());
            artifact.put("version", ARTIFACT_VERSION);
            artifact.put("requestId", identity.requestId());
            artifact.put("content", mapper.convertValue(content, Map.class));
            update.put(GraphStateKeys.PUBLISHED_ARTIFACT, artifact);
            // 落库最终消息
            persistFinalQuietly(identity, content);
            // 记录澄清问题
            nodeMemoryRecorder.clarificationAsked(identity.conversationId(), identity.userId(),
                    identity.requestId(), pauseId, output.questions());
        } catch (Exception exception) {
            log.warn("澄清产物投影失败 conversationId 状态缺失或序列化异常", exception);
        }
        return update;
    }

    /**
     * 结构校验总入口：字段级约束 + 跨字段一致性，各类检查各自收集、互不短路。
     *
     * @param merged 合并后的需求数组
     * @return 问题清单；空表示通过
     */
    private List<String> checkStructure(List<StoredNeed> merged, OverAllState state) {
        if (merged.isEmpty()) {
            return List.of("needs 不能为空；信息不足时应输出 needs_clarification=true");
        }
        List<String> problems = new ArrayList<>(checkFields(merged));
        problems.addAll(checkEvidenceIds(merged));
        problems.addAll(checkEvidenceDepends(merged));
        problems.addAll(checkEvidenceOrder(merged));
        problems.addAll(checkAdviceShape(merged));
        problems.addAll(checkConditions(merged));
        problems.addAll(checkNeedDepends(merged));
        problems.addAll(checkInstructionWording(merged, state));
        return problems;
    }

    /** 字段级约束：Bean Validation 注解（必填、非空、枚举取值等）。 */
    private List<String> checkFields(List<StoredNeed> merged) {
        List<String> problems = new ArrayList<>();
        for (StoredNeed stored : merged) {
            Need need = stored.need();
            for (ConstraintViolation<Need> violation : validator.validate(need)) {
                problems.add(need.id() + " " + violation.getPropertyPath() + ": " + violation.getMessage());
            }
        }
        return problems;
    }

    /**
     * 证据项 id 的格式与唯一性：必须以所属需求 id 加短横线开头，需求内唯一、全局唯一。
     *
     * <p>前缀规则与全局唯一是同一件事的两面：id 是证据链的 key，全局不能撞；让模型自己
     * 记住不撞不可靠，所以用 {@code N1-} 这样的前缀把命名空间绑到需求上。全局唯一仍然要查，
     * 因为模型可能把 {@code N2-E1} 写进 N1。</p>
     */
    private List<String> checkEvidenceIds(List<StoredNeed> merged) {
        List<String> problems = new ArrayList<>();
        Map<String, String> itemOwner = new LinkedHashMap<>();
        for (StoredNeed stored : merged) {
            Need need = stored.need();
            String prefix = need.id() + "-";
            Set<String> localIds = new HashSet<>();
            for (EvidenceItem item : need.evidencePlan()) {
                // 必须以 需求id- 开头
                if (!item.id().startsWith(prefix)) {
                    problems.add(need.id() + " 的证据项 " + item.id() + " 必须以 \"" + prefix + "\" 开头");
                }
                // 需求内唯一
                if (!localIds.add(item.id())) {
                    problems.add(need.id() + " 的证据项 id " + item.id() + " 在本需求内重复");
                    continue;
                }
                // 证据id全局唯一
                String owner = itemOwner.putIfAbsent(item.id(), need.id());
                if (owner != null) {
                    problems.add("证据项 id " + item.id() + " 已被需求 " + owner + " 占用；"
                            + "它是证据链的 key，必须全局唯一");
                }
            }
        }
        return problems;
    }

    /**
     * 证据项之间的依赖：不能依赖自身，依赖项必须在合并后的清单里存在。
     *
     * <p>引用可以跨需求——证据项 id 全局唯一，代码按 id 去证据链取那个值，不关心它由哪条
     * 需求产出。跨需求引用形成的需求级依赖由 {@link NeedDependencies} 算出，这里只判存在性。</p>
     *
     * <p>引用方要一起报出来：重写一条需求时若把被别的需求引用的证据项删掉，光说"不存在"
     * 模型不知道该改谁，得点名。</p>
     */
    private List<String> checkEvidenceDepends(List<StoredNeed> merged) {
        Set<String> allIds = evidenceIds(merged);
        List<String> problems = new ArrayList<>();
        Map<String, Set<String>> referencedBy = new LinkedHashMap<>();
        for (StoredNeed stored : merged) {
            for (EvidenceItem item : stored.need().evidencePlan()) {
                for (String dependency : item.depends()) {
                    referencedBy.computeIfAbsent(dependency, key -> new LinkedHashSet<>())
                            .add(stored.need().id());
                }
            }
        }
        for (StoredNeed stored : merged) {
            for (EvidenceItem item : stored.need().evidencePlan()) {
                for (String dependency : item.depends()) {
                    // 不能依赖自身
                    if (dependency.equals(item.id())) {
                        problems.add(stored.need().id() + " 的证据项 " + item.id() + " 不能依赖自身");
                    }
                    // 依赖项必须在清单里存在（可以先引用别的需求已列出的项）
                    else if (!allIds.contains(dependency)) {
                        problems.add(stored.need().id() + " 的证据项 " + item.id()
                                + " 依赖了不存在的证据项 " + dependency
                                + "；它被需求 " + String.join("、", referencedBy.getOrDefault(dependency, Set.of()))
                                + " 引用。如果是重写时删掉了它，请同轮把引用改成新 id，或把它原样带上");
                    }
                }
            }
        }
        return problems;
    }

    /**
     * 证据项按执行顺序排列：被依赖的同需求项必须写在前面。
     *
     * <p>顺序即执行顺序，执行侧一次线性遍历就够，不需要拓扑调度。跨需求的依赖不占位次
     * ——那一条由上游需求先跑完。这里同时也让人类读清单时先看到取数、再看到结论。</p>
     */
    private List<String> checkEvidenceOrder(List<StoredNeed> merged) {
        List<String> problems = new ArrayList<>();
        for (StoredNeed stored : merged) {
            List<EvidenceItem> items = stored.need().evidencePlan();
            Map<String, Integer> position = new HashMap<>();
            for (int i = 0; i < items.size(); i++) {
                position.put(items.get(i).id(), i);
            }
            for (int i = 0; i < items.size(); i++) {
                EvidenceItem item = items.get(i);
                for (String dependency : item.depends()) {
                    Integer at = position.get(dependency);
                    if (at != null && at >= i) {
                        problems.add(item.id() + " 依赖 " + dependency + "，但 " + dependency
                                + " 排在它后面；证据项按执行顺序排，被依赖的写在前面");
                    }
                }
            }
        }
        return problems;
    }

    /**
     * ADVICE 只出方案：恰好一条 {@code PROPOSAL} 项，自己不查不算。
     *
     * <p>方案由方案子图产出，经用户选择与可行性验证后派生需求把细节算清。ADVICE 缺事实时
     * 应拆一条 DATA 需求再引用它，而不是在自己的清单里塞 SQL/PYTHON。</p>
     */
    private List<String> checkAdviceShape(List<StoredNeed> merged) {
        List<String> problems = new ArrayList<>();
        for (StoredNeed stored : merged) {
            Need need = stored.need();
            List<EvidenceItem> proposals = need.evidencePlan().stream()
                    .filter(item -> item.source() == EvidenceSource.PROPOSAL)
                    .toList();
            if (need.intent() == NeedIntent.ADVICE) {
                // 交接说明超长度上限，基本就是在替方案子图定手段了
                for (EvidenceItem proposal : proposals) {
                    if (proposal.instruction().length() > PROPOSAL_INSTRUCTION_LIMIT) {
                        problems.add(proposal.id() + " 的 instruction 有 " + proposal.instruction().length()
                                + " 字，超过 " + PROPOSAL_INSTRUCTION_LIMIT + " 字上限；这一项只写「这次用户提出的"
                                + "特殊约束和注意事项」，不要列手段、步骤或评估维度——用户没提约束就写「无特殊约束」");
                    }
                }
                if (need.evidencePlan().size() != 1 || proposals.size() != 1) {
                    problems.add(need.id() + " 是 ADVICE，证据清单只能有一条 source=PROPOSAL 的方案项；"
                            + "现在共 " + need.evidencePlan().size() + " 条，其中方案项 " + proposals.size() + " 条。"
                            + "要查的事实请拆成 DATA 需求再引用");
                }
            } else if (!proposals.isEmpty()) {
                problems.add(need.id() + " 是 DATA，不能有 source=PROPOSAL 的证据项；方案请放进 ADVICE 需求");
            }
        }
        return problems;
    }

    /** PROPOSAL 项 instruction 的字数上限：它是交接注意事项，不是方案说明。 */
    private static final int PROPOSAL_INSTRUCTION_LIMIT = 120;

    /**
     * instruction 措辞守卫：取数与计算项只写业务口径。
     *
     * <p>表名、字段名一旦写进需求，就等于让 planner 替取数子图做选择，也和"业务口径"的定位冲突。
     * 命中的名字来自本轮授权表结构，所以判的是"这套库里真实存在的标识符"，不是英文单词。</p>
     */
    private List<String> checkInstructionWording(List<StoredNeed> merged, OverAllState state) {
        Set<String> schemaNames = schemaIdentifiers(state);
        if (schemaNames.isEmpty()) {
            return List.of();
        }
        List<String> problems = new ArrayList<>();
        for (StoredNeed stored : merged) {
            for (EvidenceItem item : stored.need().evidencePlan()) {
                if (item.source() == EvidenceSource.PROPOSAL) {
                    continue;
                }
                Matcher matcher = SCHEMA_IDENTIFIER.matcher(item.instruction());
                Set<String> hits = new LinkedHashSet<>();
                while (matcher.find()) {
                    String token = matcher.group();
                    if (schemaNames.contains(token)) {
                        hits.add(token);
                    }
                }
                if (!hits.isEmpty()) {
                    problems.add(item.id() + " 的 instruction 写了表名 / 字段名 " + hits
                            + "；只写业务口径（如「累计已入库量」「审核时间」），选表和取哪个字段由取数子图决定");
                }
            }
        }
        return problems;
    }

    /**
     * 从授权表结构里取出真实存在的表名与字段名。
     *
     * <p>没有表结构（如未鉴权的调用路径）时返回空集，措辞守卫自然跳过。</p>
     */
    private Set<String> schemaIdentifiers(OverAllState state) {
        Map<String, Object> schemaRef = state.value(GraphStateKeys.AUTHORIZED_SCHEMA_REF, Map.of());
        Object raw = schemaRef.get("schema");
        if (raw == null) {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        Matcher table = SCHEMA_TABLE.matcher(raw.toString());
        while (table.find()) {
            names.add(table.group(1));
        }
        Matcher column = SCHEMA_COLUMN.matcher(raw.toString());
        while (column.find()) {
            names.add(column.group(1));
        }
        return names;
    }

    /** 表结构里的表名：{@code # Table: purchase_order, 采购订单}。 */
    private static final Pattern SCHEMA_TABLE = Pattern.compile("# Table: ([a-z][a-z0-9_]*)");

    /** 表结构里的字段名：{@code (inbound_qty:bigint, ...)}。 */
    private static final Pattern SCHEMA_COLUMN = Pattern.compile("\\(([a-z][a-z0-9_]*):");

    /** instruction 里的小写标识符候选（表名与字段名都是小写下划线形式）。 */
    private static final Pattern SCHEMA_IDENTIFIER = Pattern.compile("[a-z][a-z0-9_]{2,}");

    /** 条件开关的引用：left 必须形如 {@code evidence.<证据项 id>}，且指向真实存在的证据项。 */
    private List<String> checkConditions(List<StoredNeed> merged) {
        Set<String> evidenceIds = evidenceIds(merged);
        List<String> problems = new ArrayList<>();
        for (StoredNeed stored : merged) {
            Need need = stored.need();
            if (need.condition() == null) {
                continue;
            }
            String reference = need.condition().left();
            if (!reference.startsWith(EVIDENCE_PREFIX)) {
                // 必须形如 evidence.xxx
                problems.add(need.id() + " 的 condition.left 必须形如 evidence.<证据项 id>");
                continue;
            }
            String referenced = reference.substring(EVIDENCE_PREFIX.length());
            if (!evidenceIds.contains(referenced)) {
                // 引用的证据项必须存在
                problems.add(need.id() + " 的 condition.left 引用了不存在的证据项 " + referenced);
                continue;
            }
            if (need.condition().right() instanceof String text && text.isBlank()) {
                // 真实踩过的坑：拿空字符串当"清单非空"用，比较器只会得到类型不一致，条件永远不成立
                problems.add(need.id() + " 的 condition.right 是空字符串；要判「有没有结果」，"
                        + "请加一条数量证据项（如「滞销品数量（个）」），condition 改成与 0 比较");
            }
            String label = itemLabels(merged).get(referenced);
            if (label != null && LIST_LABEL.matcher(label).find() && !SCALAR_LABEL.matcher(label).find()) {
                problems.add(need.id() + " 的 condition.left 引用的 " + referenced + "（" + label
                        + "）是清单类结果，比较器只能比标量；请先加一条数量证据项，再让 condition 引用它");
            }
        }
        return problems;
    }

    /**
     * 需求之间的依赖：不能依赖自身，依赖的需求必须存在，且有效依赖图不能成环。
     *
     * <p>成环按有效依赖图判：需求级 {@code depends}、证据项跨需求引用、{@code condition}
     * 引用三处并起来。反向依赖（N1 等 N2，而 N2 又引用了 N1 的证据）在这里与成环同一次判断。</p>
     */
    private List<String> checkNeedDepends(List<StoredNeed> merged) {
        Map<String, Need> known = new LinkedHashMap<>();
        for (StoredNeed stored : merged) {
            known.put(stored.need().id(), stored.need());
        }
        List<String> problems = new ArrayList<>();
        for (Need need : known.values()) {
            for (String dependency : need.depends()) {
                if (dependency.equals(need.id())) {
                    problems.add(need.id() + " 不能依赖自身");
                } else if (!known.containsKey(dependency)) {
                    problems.add(need.id() + " 依赖了不存在的需求 " + dependency);
                }
            }
        }
        String cycle = NeedDependencies.findCycle(NeedDependencies.upstream(new ArrayList<>(known.values())));
        if (!cycle.isEmpty()) {
            problems.add("需求依赖成环：" + cycle);
        }
        return problems;
    }

    /** 证据项 id 到 label 的映射，供 condition 的标量性判断使用。 */
    private Map<String, String> itemLabels(List<StoredNeed> merged) {
        Map<String, String> labels = new LinkedHashMap<>();
        for (StoredNeed stored : merged) {
            for (EvidenceItem item : stored.need().evidencePlan()) {
                labels.put(item.id(), item.label());
            }
        }
        return labels;
    }

    /** label 里出现这些词，说明这条证据项产出的是多行结果，不能拿来比大小。 */
    private static final Pattern LIST_LABEL = Pattern.compile("清单|明细|列表|排名|日表");

    /** 同一批词出现在数量类 label 里属正常（如「低库存清单的行数」），带这些词就放过。 */
    private static final Pattern SCALAR_LABEL = Pattern.compile("数量|个数|条数|项数|行数|计数|合计|总额|比率|比例|天数|布尔|是否");

    /** 合并后全部证据项 id：条件引用与跨需求引用都在这张表里解析。 */
    private Set<String> evidenceIds(List<StoredNeed> merged) {
        Set<String> ids = new HashSet<>();
        for (StoredNeed stored : merged) {
            for (EvidenceItem item : stored.need().evidencePlan()) {
                ids.add(item.id());
            }
        }
        return ids;
    }

    /**
     * 需求数量上限：只数<b>同时未完成</b>的需求。
     *
     * <p>终态需求不占名额——跑完一条就把名额还回去，否则"拆三条、跑完再补三条"会被卡死。</p>
     */
    private List<String> checkQuota(List<StoredNeed> merged) {
        List<String> problems = new ArrayList<>();
        int user = NeedStore.openCount(merged, USER_NEED_PREFIX);
        if (user > maxUserNeeds) {
            problems.add("同时未完成的用户需求 " + user + " 条，超过上限 " + maxUserNeeds);
        }
        int derived = NeedStore.openCount(merged, DERIVED_NEED_PREFIX);
        if (derived > maxDerivedNeeds) {
            problems.add("同时未完成的派生需求 " + derived + " 条，超过上限 " + maxDerivedNeeds);
        }
        return problems;
    }

    /**
     * 把本轮产出的需求并入已有需求数组。
     *
     * <p>需求 id 没出现过 → 新需求整条收录；已存在 → <b>整条替换它的计划</b>：
     * 证据清单、{@code depends}、{@code condition} 一起换（它们是一个整体，冻结引用会在
     * 证据项换 id 后留下悬空）；正文与 {@code intent} 以定稿为准，状态沿用旧值。
     * 不交回的旧证据项就是删除，换口径要用新 id。</p>
     *
     * <p>同一轮把同一个需求 id 写两次直接打回：那是重复输出，不是要改两次。</p>
     *
     * @param needs    本轮产出的需求
     * @param existing State 中已有的需求
     * @return 合并结果
     */
    private MergeOutcome merge(List<Need> needs, List<StoredNeed> existing) {
        Map<String, StoredNeed> byId = new LinkedHashMap<>();
        for (StoredNeed stored : existing) {
            byId.put(stored.need().id(), stored);
        }

        List<String> violations = new ArrayList<>();
        Set<String> roundNeedIds = new LinkedHashSet<>();
        for (Need need : needs) {
            //  同一轮不能重复给同一个需求 id
            if (!roundNeedIds.add(need.id())) {
                violations.add("本轮重复输出需求 id " + need.id() + "；同一需求只输出一次");
                continue;
            }
            StoredNeed prior = byId.get(need.id());
            //  新需求整条收录
            if (prior == null) {
                byId.put(need.id(), new StoredNeed(need, NeedStatus.PENDING));
                continue;
            }
            // 已有需求：证据清单连同它对上游的引用一起换掉
            // 正文与意图以定稿为准；depends/condition 属于"计划"的一部分，
            // 冻结它们会在证据项换 id 后留下悬空引用（实测踩到过）
            Need priorNeed = prior.need();
            Need rewritten = new Need(priorNeed.id(), priorNeed.intent(), priorNeed.need(),
                    need.depends(), need.condition(), need.evidencePlan());
            byId.put(need.id(), new StoredNeed(rewritten, prior.status()));
        }
        return new MergeOutcome(new ArrayList<>(byId.values()), violations);
    }

    /** 校验失败统一出口：问题拼成一条可执行说明，最多列 10 条。 */
    private Map<String, Object> fail(OverAllState state, List<String> problems) {
        String message = problems.size() <= MAX_REPORTED_PROBLEMS
                ? String.join("；", problems)
                : String.join("；", problems.subList(0, MAX_REPORTED_PROBLEMS))
                + "；…另有 " + (problems.size() - MAX_REPORTED_PROBLEMS) + " 条未列出";
        return formatFailure(state, Map.of("type", "NeedValidationException", "message", message));
    }

    /**
     * 组装失败分支：回填错误明细并按预算决定重试还是收场。
     *
     * <p><b>不碰需求数组</b>：已定稿的需求是别的轮次验收过的事实，不能被本轮的问题一起清掉。</p>
     */
    private Map<String, Object> formatFailure(OverAllState state, Map<String, Object> error) {
        // 校验当前会话归属
        ConversationIdentity identity = identity(state);
        // 记录错误反馈
        recordFeedback(identity, error);
        // 取出当前重试次数 REPAIR_COUNT
        int retryCount = state.value(GraphStateKeys.REPAIR_COUNT, 0);
        Map<String, Object> update = new HashMap<>();
        update.put(GraphStateKeys.REPAIR_ERROR, error);
        if (retryCount < maxFormatRetries) {
            // 重试次数未到上限，更新状态为 WAITING_HUMAN 并重置修复预算和审核计数
            update.put(GraphStateKeys.REPAIR_COUNT, retryCount + 1);
            update.put(GraphStateKeys.NEED_CHECK_RESULT, NeedCheckResult.RETRY_FORMAT.name());
            return update;
        }
        // 重试次数已达上限，更新状态为 FORMAT_RETRY_EXHAUSTED 并记录错误
        update.put(GraphStateKeys.REPAIR_COUNT, retryCount);
        update.put(GraphStateKeys.NEED_CHECK_RESULT, NeedCheckResult.FORMAT_RETRY_EXHAUSTED.name());
        if (identity != null) {
            // 记录错误反馈
            persistFinalQuietly(identity, visibleResults.planIncomplete(
                    threads.of(identity.userId(), identity.conversationId())));
            nodeMemoryRecorder.formatExhausted(identity.conversationId(), identity.userId(),
                    identity.requestId());
        }
        return update;
    }

    /** 把解析异常整理成 planner 可读的明细：类型、原文消息、字段路径与出错位置。 */
    private Map<String, Object> describeParseError(JsonProcessingException error) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("type", error.getClass().getSimpleName());
        detail.put("message", error.getOriginalMessage());
        if (error instanceof JsonMappingException mapping) {
            StringBuilder path = new StringBuilder();
            for (JsonMappingException.Reference reference : mapping.getPath()) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                if (reference.getFieldName() != null) {
                    path.append(reference.getFieldName());
                } else {
                    path.append('[').append(reference.getIndex()).append(']');
                }
            }
            detail.put("path", path.toString());
        }
        if (error.getLocation() != null) {
            detail.put("line", error.getLocation().getLineNr());
            detail.put("column", error.getLocation().getColumnNr());
        }
        return detail;
    }

    /** 落库一条最终消息；已有 final 则跳过，失败只记日志。 */
    private void persistFinalQuietly(ConversationIdentity identity, VisibleMessageContent content) {
        try {
            // 检查是否已有 final 消息
            if (messageStore.findFinal(identity.conversationId(), identity.userId(), identity.requestId()) != null) {
                return;
            }
            // 落库最终消息
            messageStore.append(identity.conversationId(), identity.userId(), identity.requestId(),
                    ROLE_ASSISTANT, AiMessageKind.ASSISTANT_FINAL, null, null, content);
        } catch (Exception exception) {
            log.warn("最终消息发布拍保存失败", exception);
        }
    }

    /** 把失败明细写进 planner 的节点记忆，下一轮读取后能避免犯同样的错。 */
    private void recordFeedback(ConversationIdentity identity, Map<String, Object> failure) {
        if (identity == null) {
            return;
        }
        nodeMemoryRecorder.formatFeedback(identity.conversationId(), identity.userId(),
                identity.requestId(), failure);
    }

    /** 解析 State 里的会话归属；缺失或非法时返回 null，调用方跳过落库与记忆写入。 */
    private ConversationIdentity identity(OverAllState state) {
        try {
            Object conversationId = state.value(GraphStateKeys.CONVERSATION_ID).orElse(null);
            Object userId = state.value(GraphStateKeys.USER_ID).orElse(null);
            if (conversationId == null || userId == null) {
                return null;
            }
            return new ConversationIdentity(Long.parseLong(conversationId.toString()),
                    Long.parseLong(userId.toString()), state.value(GraphStateKeys.REQUEST_ID, ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    /**
     * @param merged     合并后的需求数组
     * @param violations 违规说明；为空表示本轮产出可以并入
     */
    private record MergeOutcome(List<StoredNeed> merged, List<String> violations) {
    }

    /**
     * @param conversationId 会话 ID
     * @param userId         用户 ID
     * @param requestId      本轮请求标识
     */
    private record ConversationIdentity(long conversationId, long userId, String requestId) {
    }
}