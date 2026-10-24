package com.qiheng.erp.ai.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.OpenAiCompatibleChatModel;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.constant.NeedCheckResult;
import com.qiheng.erp.ai.constant.NeedReviewResult;
import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.qiheng.erp.ai.graph.support.NodeMemoryRecorder;
import com.qiheng.erp.ai.memory.node.PlannerMemoryContext;
import com.qiheng.erp.ai.service.VisibleResultFactory;
import com.qiheng.erp.ai.service.knowledge.AuthorizedKnowledgeProvider;
import com.qiheng.erp.ai.service.knowledge.KnowledgeProvider;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 真实大模型的需求拆解集成测试。
 *
 * <p>显式使用 {@code -Dai.model.it=true} 启用；连接参数可用系统属性覆盖：
 * {@code ai.model.api-key}、{@code ai.model.base-url}、{@code ai.model.name}，
 * 表结构与测试问题里的产品、仓库都从真实库读取（{@code ai.mysql.*}）。</p>
 *
 * <p>验证三件单元测试验不了的事：真实模型能不能按协议产出需求数组、
 * 结构校验会不会误杀合法产出、业务审阅能不能真的挑出问题并让 planner 补上。</p>
 *
 * <p>测试会把每一轮的模型原始输出与判定结果打到标准输出，便于人工复核拆解质量；
 * 断言只钉住"最终要么拆出合法需求、要么合规地请求澄清"两件事，不钉具体内容。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
@EnabledIfSystemProperty(named = "ai.model.it", matches = "true")
class RealModelNeedsIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 采购员的真实授权（与 sys_role.permission_codes 一致）。 */
    private static final List<String> PURCHASER_PERMISSIONS = List.of(
            "product:query", "warehouse:query", "supplier:query", "supplier:create",
            "purchase:query", "purchase:create", "purchase:manage", "return:query",
            "dashboard:overview:query", "ai:assistant:use");
    private static ValidatorFactory validators;
    private static DataSource dataSource;
    private static String plannerPrompt;
    /** 模拟工作记忆：planner 每轮都能看见自己上一轮的原始输出（格式修复要靠它）。 */
    private final List<AssistantMessage> plannerHistory = new ArrayList<>();
    private static String reviewPrompt;

    @BeforeAll
    static void prepare() throws Exception {
        validators = Validation.buildDefaultValidatorFactory();
        // 与生产同源：只加载两份提示词，不再额外拼接清单——质量要求已经写进提示词本身
        plannerPrompt = new ClassPathResource("prompts/planner.md").getContentAsString(StandardCharsets.UTF_8);
        reviewPrompt = new ClassPathResource("prompts/need_review.md").getContentAsString(StandardCharsets.UTF_8);
        dataSource = mysqlDataSource();
    }

    @AfterAll
    static void close() {
        if (validators != null) {
            validators.close();
        }
        if (dataSource instanceof HikariDataSource hikari) {
            hikari.close();
        }
    }

    private static DataSource mysqlDataSource() {
        HikariDataSource source = new HikariDataSource();
        source.setJdbcUrl(System.getProperty("ai.mysql.url",
                "jdbc:mysql://localhost:13307/erp?useUnicode=true&characterEncoding=utf8"
                        + "&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
        source.setUsername(System.getProperty("ai.mysql.username", "root"));
        source.setPassword(System.getProperty("ai.mysql.password", "123456"));
        source.setMaximumPoolSize(2);
        return source;
    }

    private static ChatModel realModel() {
        String apiKey = System.getProperty("ai.model.api-key", System.getenv("DEEPSEEK_API_KEY"));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("未提供模型密钥：请用 -Dai.model.api-key=... 指定");
        }
        String baseUrl = System.getProperty("ai.model.base-url", "https://api.deepseek.com");
        // 与生产装配一致：规划与审阅都用 Flash
        String modelName = System.getProperty("ai.model.name", "deepseek-flash");
        // 与生产同一套装配：宽松解析网关的 "null" 枚举值，不偷偷重试
        String thinking = System.getProperty("ai.model.enable-thinking");
        Boolean enableThinking = thinking == null || thinking.isBlank() ? null : Boolean.valueOf(thinking.trim());
        return OpenAiCompatibleChatModel.create(apiKey, baseUrl, modelName, 0.2,
                java.time.Duration.ofSeconds(300), enableThinking);
    }

    /**
     * 真实模型跑一遍：拆需求 → 结构校验 → 业务审阅。
     *
     * <p>用几个真实业务问题各跑一遍，看拆出来的需求数组是否一次列全、
     * 是否会被结构校验误杀、审阅是否挑得出真问题；第四个问题专门验证
     * ADVICE 只出一条 PROPOSAL 项。</p>
     */
    @Test
    void realModelProducesStructurallyValidNeedsAcrossRealQuestions() throws Exception {
        ChatModel model = realModel();
        RecordingModel recording = new RecordingModel(model, "planner");
        // 与生产一致：planner 能从工作记忆里看到自己上一轮的输出
        PlannerMemoryContext memory = mock(PlannerMemoryContext.class);
        when(memory.read(any(), any())).thenAnswer(invocation -> List.copyOf(plannerHistory));
        PlannerNode planner = new PlannerNode(ChatClient.create(recording), plannerPrompt,
                Clock.systemDefaultZone(), mock(NodeMemoryRecorder.class), MAPPER, memory);
        NeedCheckNode needCheck = new NeedCheckNode(MAPPER, validators.getValidator(), 2, 3, 6,
                mock(NodeMemoryRecorder.class), mock(ConversationMessageStore.class), new ConversationThreads(),
                new VisibleResultFactory());
        NeedReviewNode needReview = new NeedReviewNode(ChatClient.create(new RecordingModel(model, "need_review")),
                reviewPrompt, MAPPER, 2, 6, mock(NodeMemoryRecorder.class), mock(ConversationMessageStore.class),
                new ConversationThreads(), new VisibleResultFactory(), Clock.systemDefaultZone());
        // 业务口径节点：与生产图同一条链路，权限码用采购员的真实授权
        KnowledgeProvider knowledgeProvider = new AuthorizedKnowledgeProvider();
        KnowledgeNode knowledge = new KnowledgeNode(knowledgeProvider);

        String schema = realSchema();
        String productCode = firstProductCode();
        String warehouseName = firstWarehouseName();
        List<String> questions = List.of(
                warehouseName + "的" + productCode + "下个月会不会缺货？",
                "上个月各仓库的商品销量排名前十的是哪些",
                "最近采购订单里有哪些供应商交货延期了",
                "库存低于安全库存的产品应该怎么补货？给出补货方案",
                "上个月采购金额最高的前五个供应商是哪些",
                "各仓库的库存周转天数是多少",
                "有哪些产品近30天有销售但当前可用库存为0",
                "给滞销品出一个清库方案",
                "有哪些采购订单已经超过预计到货日但还没到货",
                // 压力题：口径词在业务知识里没有定义，考的是"澄清或写明假设"，不是自己编规则
                "哪些供应商最近不守时？给我一份名单",
                "各仓库的库存周转率是多少，哪个仓最该清货");

        List<String> failures = new ArrayList<>();

        for (String question : questions) {
            System.out.println("\n================ 真实模型拆解：" + question + " ================");
            Map<String, Object> result = run(planner, needCheck, needReview, knowledge, schema, question);
            String check = String.valueOf(result.get("check"));
            String verdict = String.valueOf(result.get("verdict"));
            System.out.println(">>> 结构校验：" + check + "；业务审阅：" + verdict + "；轮次：" + result.get("rounds"));
            if (NeedCheckResult.READY.name().equals(check)) {
                System.out.println(">>> 需求数组：\n" + result.get("needs"));
            }
            // 请求澄清是合规结局：信息不足时不该硬拆
            boolean clarified = NeedCheckResult.WAITING_HUMAN.name().equals(check);
            boolean accepted = NeedCheckResult.READY.name().equals(check)
                    && NeedReviewResult.OK.name().equals(verdict);
            if (!clarified && !accepted) {
                failures.add(question + " → 结构校验=" + check + "，业务审阅=" + verdict
                        + "，错误=" + result.get("error") + "，问题=" + result.get("issues"));
            }
        }

        if (!failures.isEmpty()) {
            throw new AssertionError("以下问题没有一次拆对：\n" + String.join("\n", failures));
        }
    }

    /**
     * 跑一轮完整链路：规划 → 校验 → 审阅；审阅有问题时回规划再跑一轮。
     *
     * <p>请求澄清是终态：模型认为信息不足时继续问也是白问，直接收在这里。</p>
     */
    private Map<String, Object> run(PlannerNode planner, NeedCheckNode needCheck, NeedReviewNode needReview,
                                    KnowledgeNode knowledge, String schema, String question) {
        Map<String, Object> state = new HashMap<>();
        plannerHistory.clear();
        state.put(GraphStateKeys.USER_ID, 1L);
        state.put(GraphStateKeys.CONVERSATION_ID, 1L);
        state.put(GraphStateKeys.REQUEST_ID, "real-" + question.hashCode());
        state.put(GraphStateKeys.USER_REQUEST, question);
        state.put(GraphStateKeys.AUTHORIZED_SCHEMA_REF, Map.of("schema", schema));
        state.put(GraphStateKeys.PERMISSION_CODES, PURCHASER_PERMISSIONS);
        state.put(GraphStateKeys.NEEDS, List.of());
        state.put(GraphStateKeys.REPAIR_COUNT, 0);
        state.put(GraphStateKeys.REPAIR_ERROR, Map.of());

        OverAllState current = new OverAllState(new HashMap<>(state));
        // 先跑业务口径节点：口径必须真的进 State，否则这一轮测的还是"没注入口径"的版本
        current = new OverAllState(merge(current, knowledge.apply(current)));
        String injected = String.valueOf(current.value(GraphStateKeys.BUSINESS_KNOWLEDGE, ""));
        if (injected.isBlank()) {
            throw new AssertionError("业务口径没有注入，本轮测不到口径效果");
        }
        System.out.println("--- 已注入业务口径 " + injected.length() + " 字 ---");
        String check = "";
        String error = "";
        String verdict = "";
        String issues = "";
        int rounds = 0;

        for (int round = 0; round < 8; round++) {
            rounds++;
            Map<String, Object> plannerUpdate = planner.apply(current);
            current = new OverAllState(merge(current, plannerUpdate));
            String raw = String.valueOf(current.value(GraphStateKeys.PLANNER_OUTPUT, ""));
            // 下一轮能看到自己这轮说了什么（生产里由工作记忆记录）
            plannerHistory.add(new AssistantMessage(raw));
            System.out.println("--- 第" + rounds + "轮 planner 原始输出 ---");
            System.out.println(raw);

            Map<String, Object> checkUpdate = needCheck.apply(current);
            current = new OverAllState(merge(current, checkUpdate));
            check = String.valueOf(checkUpdate.get(GraphStateKeys.NEED_CHECK_RESULT));
            error = String.valueOf(checkUpdate.get(GraphStateKeys.REPAIR_ERROR));

            if (NeedCheckResult.WAITING_HUMAN.name().equals(check)) {
                System.out.println("--- 模型请求澄清 ---");
                System.out.println(current.value(GraphStateKeys.CLARIFICATION_QUESTIONS, List.of()));
                break;
            }
            if (!NeedCheckResult.READY.name().equals(check)) {
                if (NeedCheckResult.FORMAT_RETRY_EXHAUSTED.name().equals(check)) {
                    break;
                }
                continue;
            }

            Map<String, Object> reviewUpdate = needReview.apply(current);
            current = new OverAllState(merge(current, reviewUpdate));
            verdict = String.valueOf(reviewUpdate.get(GraphStateKeys.NEED_REVIEW_RESULT));
            issues = String.valueOf(reviewUpdate.get(GraphStateKeys.REPAIR_ISSUES));
            System.out.println("--- 第" + rounds + "轮业务审阅：" + verdict + " ---");
            if (!issues.isBlank() && !"[]".equals(issues)) {
                System.out.println(issues);
            }
            if (NeedReviewResult.OK.name().equals(verdict)
                    || NeedReviewResult.FORMAT_RETRY_EXHAUSTED.name().equals(verdict)) {
                break;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("check", check);
        result.put("error", error);
        result.put("verdict", verdict);
        result.put("issues", issues);
        result.put("rounds", rounds);
        result.put("needs", String.valueOf(current.value(GraphStateKeys.NEEDS, List.of())));
        return result;
    }

    private Map<String, Object> merge(OverAllState current, Map<String, Object> update) {
        Map<String, Object> merged = new HashMap<>(current.data());
        merged.putAll(update);
        return merged;
    }

    /**
     * 真实库上的授权 Schema：只给登记且启用的表与列。
     */
    private String realSchema() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        List<Map<String, Object>> tables = jdbc.queryForList(
                "SELECT table_name, business_name, description FROM ai_data_table "
                        + "WHERE status = '1' AND deleted = 0 ORDER BY table_name");
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> table : tables) {
            String name = String.valueOf(table.get("table_name"));
            sb.append("# Table: ").append(name).append(", ").append(table.get("business_name")).append('\n');
            if (table.get("description") != null) {
                sb.append("# ").append(table.get("description")).append('\n');
            }
            List<Map<String, Object>> columns = jdbc.queryForList(
                    "SELECT column_name, column_type, business_name, description, primary_key, unique_key "
                            + "FROM ai_data_table_column WHERE table_name = ? AND status = '1' AND deleted = 0 "
                            + "ORDER BY id", name);
            sb.append("[\n");
            List<String> lines = new ArrayList<>();
            for (Map<String, Object> column : columns) {
                StringBuilder line = new StringBuilder("(").append(column.get("column_name"))
                        .append(':').append(column.get("column_type"));
                String comment = column.get("description") != null ? column.get("description").toString()
                        : String.valueOf(column.get("business_name"));
                if (comment != null && !comment.isBlank()) {
                    line.append(", ").append(comment);
                }
                if (Integer.valueOf(1).equals(toInt(column.get("primary_key")))) {
                    line.append(", Primary Key");
                } else if (Integer.valueOf(1).equals(toInt(column.get("unique_key")))) {
                    line.append(", Unique");
                }
                lines.add(line.append(")").toString());
            }
            sb.append(String.join(",\n", lines)).append("\n]\n");
        }
        return sb.toString();
    }

    /**
     * 取一个真实产品编码：测试问题必须指向真实数据，否则模型只能请求澄清。
     */
    private String firstProductCode() {
        List<String> codes = new JdbcTemplate(dataSource).queryForList(
                "SELECT product_code FROM product WHERE deleted = 0 AND status = '1' ORDER BY id LIMIT 1",
                String.class);
        if (codes.isEmpty()) {
            throw new IllegalStateException("真实库里没有可用产品，无法构造可回答的测试问题");
        }
        return codes.getFirst();
    }

    /**
     * 取一个真实仓库名。
     */
    private String firstWarehouseName() {
        List<String> names = new JdbcTemplate(dataSource).queryForList(
                "SELECT warehouse_name FROM warehouse WHERE deleted = 0 AND status = '1' ORDER BY id LIMIT 1",
                String.class);
        if (names.isEmpty()) {
            throw new IllegalStateException("真实库里没有可用仓库，无法构造可回答的测试问题");
        }
        return names.getFirst();
    }

    private Integer toInt(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.valueOf(value.toString());
    }

    /**
     * 包一层记录每次调用的提示词：模型每一轮真正看到的输入都要能被人复核，
     * 输入组装改了就看得出来。
     */
    private static final class RecordingModel implements ChatModel {

        private final ChatModel delegate;
        private final String label;

        private RecordingModel(ChatModel delegate, String label) {
            this.delegate = delegate;
            this.label = label;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            long knowledgeChars = prompt.getInstructions().stream()
                    .map(message -> message.getText() == null ? "" : message.getText())
                    .filter(text -> text.contains("业务口径"))
                    .mapToLong(String::length).sum();
            System.out.println("--- [" + label + "] system 段里的业务口径长度：" + knowledgeChars + " ---");
            System.out.println("--- [" + label + "] 输入（user 段）---");
            prompt.getInstructions().stream()
                    .filter(message -> message.getMessageType()
                            == org.springframework.ai.chat.messages.MessageType.USER)
                    .forEach(message -> System.out.println(message.getText()));
            System.out.println("--- [" + label + "] 输入结束 ---");
            return delegate.call(prompt);
        }
    }
}
