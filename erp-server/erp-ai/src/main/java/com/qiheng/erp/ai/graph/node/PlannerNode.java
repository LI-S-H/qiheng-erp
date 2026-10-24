package com.qiheng.erp.ai.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.constant.PlannerMode;
import com.qiheng.erp.ai.domain.dto.needs.PlannerOutput;
import com.qiheng.erp.ai.graph.support.NeedStore;
import com.qiheng.erp.ai.graph.support.NodeMemoryRecorder;
import com.qiheng.erp.ai.graph.support.TimeScope;
import com.qiheng.erp.ai.memory.node.PlannerMemoryContext;
import com.qiheng.erp.ai.memory.node.WorkingMemoryTranscript;
import com.qiheng.erp.ai.service.progress.RunProgressPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.converter.BeanOutputConverter;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 需求拆解节点：只把用户目标拆成需求数组，不取数、不判定完成。
 *
 * <p><b>输入分三段</b>：</p>
 * <ol>
 *   <li><b>system prompt</b>——本轮任务内恒定的部分：静态任务说明、当前可见的表结构、
 *       输出格式说明。表结构放这里而不是每轮拼进 user input，因为它一轮任务内不变，
 *       每轮重复只会稀释注意力。</li>
 *   <li><b>user input</b>——每轮动态的部分：轮次头 + 本轮任务句 + 本轮上下文。
 *       每一段独立判断有没有内容，没有就整段不出现。</li>
 *   <li><b>节点记忆</b>——本轮真正喂给模型的输入与模型输出，不记 system prompt。
 *       工作记忆里存的是纯文本转写，一行 = 模型的一条消息，下一轮按序原样塞回。</li>
 * </ol>
 *
 * <p><b>本轮任务句只做指路</b>：具体怎么做写在系统提示词的任务表里，输入里重复一遍
 * 既费 token，又可能与提示词措辞不同步。任务句用自然语言，不把内部枚举名
 * （INITIAL/REFINE）塞给模型——它不认识这些词，也不需要认识。</p>
 *
 * @author Li
 * @since 2026-10-08
 */
@Slf4j
public class PlannerNode implements NodeAction {

    /** 轮次头里的时间格式：比 ISO 带偏移的形式更好读。 */
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    /** 时间口径段里的日期格式。 */
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ChatClient chatClient;
    private final String systemPrompt;
    private final Clock clock;
    private final NodeMemoryRecorder nodeMemoryRecorder;
    private final ObjectMapper mapper;
    private final PlannerMemoryContext memoryContext;
    private final RunProgressPublisher progressPublisher;
    private final BeanOutputConverter<PlannerOutput> converter = new BeanOutputConverter<>(PlannerOutput.class);

    public PlannerNode(ChatClient chatClient, String systemPrompt, Clock clock,
                       NodeMemoryRecorder nodeMemoryRecorder) {
        this(chatClient, systemPrompt, clock, nodeMemoryRecorder, new ObjectMapper(), null, null);
    }

    public PlannerNode(ChatClient chatClient, String systemPrompt, Clock clock,
                       NodeMemoryRecorder nodeMemoryRecorder, ObjectMapper mapper,
                       PlannerMemoryContext memoryContext) {
        this(chatClient, systemPrompt, clock, nodeMemoryRecorder, mapper, memoryContext, null);
    }

    public PlannerNode(ChatClient chatClient, String systemPrompt, Clock clock,
                       NodeMemoryRecorder nodeMemoryRecorder, ObjectMapper mapper,
                       PlannerMemoryContext memoryContext, RunProgressPublisher progressPublisher) {
        this.chatClient = chatClient;
        this.systemPrompt = systemPrompt;
        this.clock = clock;
        this.nodeMemoryRecorder = nodeMemoryRecorder;
        this.mapper = mapper;
        this.memoryContext = memoryContext;
        this.progressPublisher = progressPublisher;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        // 读模式和轮次
        PlannerMode mode = readMode(state);
        int round = state.value(GraphStateKeys.ROUND_NO, 0) + 1;
        // 算时间和任务句
        OffsetDateTime now = OffsetDateTime.now(clock);
        String time = now.format(TIME_FORMAT);
        // 获取错误信息
        Map<?, ?> repairError = state.value(GraphStateKeys.REPAIR_ERROR, Map.of());
        boolean repairing = !repairError.isEmpty();
        // 本轮任务句
        String task = taskSentence(mode, repairing, repairError);
        // 组装 user input
        String userInput = buildUserInput(state, mode, round, time, task, now);
        // 输入标记：加一次，喂给模型与记进工作记忆用的是同一个字符串
        String markedInput = WorkingMemoryTranscript.input(userInput);
        // 读取历史消息；压缩历史时同步推送进度，避免界面在摘要调用期间无响应
        AtomicLong compactingStartedAt = new AtomicLong();
        List<Message> history = memoryContext == null
                ? List.of()
                : memoryContext.read(state, compacting ->
                        reportCompaction(state, compacting, compactingStartedAt));

        // 输入在本节点内持久化：恢复入口只把回答写进 State，重进 planner 并真实消费后才记一条输入。
        MemoryIdentity identity = identity(state);
        if (identity != null) {
            nodeMemoryRecorder.plannerInput(identity.conversationId(), identity.userId(),
                    identity.requestId(), round, time, task, markedInput);
        }

        // 调用模型生成输出
        String output = chatClient.prompt()
                .system(buildSystemPrompt(state))
                .messages(history)
                .user(markedInput)
                .call()
                .content();

        if (output == null) {
            output = "";
        }

        if (identity != null) {
            // 模型原始输出照原样进工作记忆：下一轮模型要看得见自己上轮说了什么
            nodeMemoryRecorder.modelOutput(identity.conversationId(), identity.userId(),
                    identity.requestId(), round, output);
        }

        // 不在这里重置重试次数，否则每次回到 planner 都会重新获得重试额度。
        return Map.of(GraphStateKeys.PLANNER_OUTPUT, output, GraphStateKeys.ROUND_NO, round);
    }

    /**
     * 组装 system prompt：静态任务说明 + 业务口径 + 当前可见表结构 + 输出格式说明。
     *
     * <p>顺序有讲究：业务口径与表结构都是一轮任务内恒定的"事实底座"，
     * 放前面让模型先建立口径；输出格式说明放最后，紧挨生成点，模型不容易漏字段。</p>
     */
    private String buildSystemPrompt(OverAllState state) {
        StringBuilder system = new StringBuilder(systemPrompt);
        String knowledge = knowledgeText(state);
        if (!knowledge.isBlank()) {
            // 追加一段业务知识段
            section(system, "业务知识", knowledge);
            system.append('\n');
        }
        Map<String, Object> schemaRef = state.value(GraphStateKeys.AUTHORIZED_SCHEMA_REF, Map.of());
        Object schema = schemaRef.get("schema");
        if (schema != null && !schema.toString().isBlank()) {
            // 追加一段当前可见数据表结构段
            section(system, "当前可见数据表结构", schema.toString().trim());
            system.append('\n');
        }
        system.append(converter.getFormat());
        return system.toString();
    }

    /**
     * 业务口径文本：knowledge 节点按权限码装配好的结果。
     */
    private String knowledgeText(OverAllState state) {
        Object knowledge = state.value(GraphStateKeys.BUSINESS_KNOWLEDGE, "");
        return knowledge == null ? "" : knowledge.toString().trim();
    }

    /**
     * 组装 user input：轮次头 + 本轮任务句 + 本轮上下文。
     *
     * <p>每一段独立判断有没有内容，没有就整段不出现——避免拼出空标题，
     * 也避免把"这一段这次没有"这件事讲给模型听。</p>
     */
    private String buildUserInput(OverAllState state, PlannerMode mode, int round, String time, String task,
                                      OffsetDateTime now) {
        StringBuilder input = new StringBuilder();
        input.append("第 ").append(round).append(" 轮 | ").append(time).append('\n');
        input.append("本轮任务：").append(task).append("\n\n");

        section(input, "时间口径", timeScope(now));
        section(input, "原始需求", state.value(GraphStateKeys.USER_REQUEST, ""));
        section(input, "已确认的澄清",
                renderClarification(state.value(GraphStateKeys.CLARIFICATION_HISTORY, List.of())));
        List<NeedStore.StoredNeed> needs = NeedStore.read(state, mapper);
        Object needsView = needs.isEmpty() ? null : NeedStore.toModelView(needs, mapper);
        section(input, "已有需求", render(needsView));

        if (mode == PlannerMode.REFINE) {
            section(input, "待补齐的缺口", render(state.value(GraphStateKeys.REFINE_GAPS, List.of())));
        }

        Map<?, ?> repairError = state.value(GraphStateKeys.REPAIR_ERROR, Map.of());
        if (!repairError.isEmpty()) {
            // 上一轮输出不再重复注入：它就是历史里的最后一条 [输出]，输入里只带本轮的新反馈
            section(input, "具体错误", render(repairError));
            section(input, "审阅反馈", render(state.value(GraphStateKeys.REPAIR_ISSUES, List.of())));
        }
        return input.toString();
    }

    /**
     * 渲染已确认的澄清：前端提交时已把「问题+答案」拼成文本，这里原样搬进本轮输入。
     *
     * <p>答案串本身就是一问一答的成对文本（问题在前、回答在后，多轮以空行分隔），
     * 后端不再重新配对；只有答案缺失时才退回渲染问题原文。</p>
     */
    private String renderClarification(Object value) {
        if (!(value instanceof Collection<?> entries) || entries.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> record)) {
                continue;
            }
            String answer = render(record.get("answer"));
            if (answer.isBlank()) {
                answer = renderQuestions(record.get("questions"));
            }
            if (answer.isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append("\n\n");
            }
            text.append(answer);
        }
        return text.toString();
    }

    /**
     * 渲染澄清问题原文：只在答案缺失时兜底使用。
     */
    private String renderQuestions(Object value) {
        if (!(value instanceof Collection<?> questions) || questions.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Object question : questions) {
            String content = render(question);
            if (content.isBlank()) {
                continue;
            }
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(content);
        }
        return text.toString();
    }

    /**
     * 本轮任务句：用自然语言说清这轮要干什么。
     *
     * <p>任务句只做指路——具体怎么做写在系统提示词的任务表里，输入里重复一遍
     * 既费 token，又可能与提示词措辞不同步。修复不是第四种模式，是同一模式的重做，
     * 所以任务句会覆盖成"修正输出"或"补齐证据"。</p>
     */
    private String taskSentence(PlannerMode mode, boolean repairing, Map<?, ?> repairError) {
        if (repairing) {
            if ("NeedReviewIssues".equals(repairError.get("type"))) {
                return "补齐证据";
            }
            return "修正输出";
        }
        return switch (mode) {
            case INITIAL -> "拆解需求";
            case REFINE -> "补充缺口";
        };
    }

    /**
     * 时间口径：把"本月/上月/下月"算好给模型。
     *
     * <p>相对日期让模型自己推月份边界是易错点，代码算好更可靠。压成一行是因为
     * 它每轮都重复，而值又完全不变，多行排版只是白占 token。</p>
     */
    private String timeScope(OffsetDateTime now) {
        return TimeScope.text(now);
    }

    /**
     * 追加一段；内容为空就整段不出现。
     */
    private void section(StringBuilder target, String title, Object content) {
        String body = render(content);
        if (body.isBlank()) {
            return;
        }
        target.append("## ").append(title).append('\n').append(body).append("\n\n");
    }

    /**
     * 把 State 里的值渲染成模型可读的正文：对象与列表走 JSON，字符串原样。
     *
     * <p>空集合与空对象渲染成空串——这一段就没有内容，不该拼出一个空标题。</p>
     */
    private String render(Object value) {
        switch (value) {
            case null -> {
                return "";
            }
            case String text -> {
                return text.trim();
            }
            case Collection<?> collection when collection.isEmpty() -> {
                return "";
            }
            case Map<?, ?> map when map.isEmpty() -> {
                return "";
            }
            default -> {
            }
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            log.warn("State 值序列化失败，退回字符串表示", error);
            return String.valueOf(value);
        }
    }

    /**
     * 读取运行模式；缺省按首轮拆解处理。
     */
    private PlannerMode readMode(OverAllState state) {
        Object mode = state.value(GraphStateKeys.PLANNER_MODE, "");
        if (PlannerMode.REFINE.name().equals(mode)) {
            return PlannerMode.REFINE;
        }
        return PlannerMode.INITIAL;
    }

    /** 记忆写入所需的会话归属与请求标识。 */
    private record MemoryIdentity(long conversationId, long userId, String requestId) {
    }

    /**
     * 压缩进度：开始记时刻并推事件，结束算耗时并收尾。
     *
     * <p>时刻存在调用内的 {@link AtomicLong} 里，不落实例字段——同一个 planner 节点
     * 会被不同会话并发复用，实例字段会串号。</p>
     */
    private void reportCompaction(OverAllState state, boolean compacting, AtomicLong startedAt) {
        if (progressPublisher == null) {
            return;
        }
        MemoryIdentity identity = identity(state);
        if (identity == null) {
            return;
        }
        if (compacting) {
            startedAt.set(System.currentTimeMillis());
            progressPublisher.nodeStarted(identity.conversationId(), GraphStateKeys.MEMORY_COMPACT_NODE);
            return;
        }
        long begin = startedAt.get();
        int duration = begin == 0 ? 0 : (int) (System.currentTimeMillis() - begin);
        progressPublisher.nodeFinished(identity.conversationId(), GraphStateKeys.MEMORY_COMPACT_NODE, duration);
    }

    /**
     * 解析 State 中的会话归属；缺失或非法时返回 null，调用方跳过记忆写入。
     */
    private MemoryIdentity identity(OverAllState state) {
        try {
            Object conversationIdObj = state.value(GraphStateKeys.CONVERSATION_ID).orElse(null);
            Object userIdObj = state.value(GraphStateKeys.USER_ID).orElse(null);
            if (conversationIdObj == null || userIdObj == null) {
                return null;
            }
            return new MemoryIdentity(Long.parseLong(conversationIdObj.toString()),
                    Long.parseLong(userIdObj.toString()), state.value(GraphStateKeys.REQUEST_ID, ""));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}