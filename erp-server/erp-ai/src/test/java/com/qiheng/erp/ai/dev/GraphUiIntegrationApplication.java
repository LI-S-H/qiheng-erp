package com.qiheng.erp.ai.dev;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.GraphConfiguration;
import com.qiheng.erp.ai.graph.GraphTopology;
import com.qiheng.erp.ai.config.RedisSaverConfiguration;
import com.qiheng.erp.ai.controller.GraphController;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.mapper.AiNodeMemoryMapper;
import com.qiheng.erp.ai.mapper.AiNodeWorkingMemoryMapper;
import com.qiheng.erp.ai.memory.node.NodeMemoryStore;
import com.qiheng.erp.ai.service.VisibleMessageCodec;
import com.qiheng.erp.ai.service.VisibleResultFactory;
import com.qiheng.erp.ai.service.knowledge.AuthorizedKnowledgeProvider;
import com.qiheng.erp.ai.graph.support.ConversationExecutionLock;
import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.qiheng.erp.ai.service.impl.GraphServiceImpl;
import com.qiheng.erp.ai.graph.support.NodeMemoryRecorder;
import com.qiheng.erp.ai.graph.runtime.RunCoordinator;
import com.qiheng.erp.ai.graph.runtime.RunFailureHandler;
import com.qiheng.erp.ai.graph.runtime.RunResumePreparer;
import com.qiheng.erp.ai.graph.runtime.RunStartPreparer;
import com.qiheng.erp.ai.graph.runtime.RunStateResolver;
import com.qiheng.erp.ai.service.progress.RunProgressPublisher;
import com.qiheng.erp.ai.service.data.registry.StubSchemaProvider;
import com.qiheng.erp.ai.graph.node.DataAccessNode;
import com.qiheng.erp.ai.graph.node.NeedCheckNode;
import com.qiheng.erp.ai.graph.node.NeedReviewNode;
import com.qiheng.erp.ai.graph.node.KnowledgeNode;
import com.qiheng.erp.ai.graph.node.NeedSelectorNode;
import com.qiheng.erp.ai.graph.node.PlannerNode;
import com.qiheng.erp.common.config.JacksonConfig;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.Result;
import com.qiheng.erp.security.config.SaTokenConfig;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.interceptor.SaTokenExceptionHandler;
import jakarta.validation.Validator;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 仅用于本地 UI 联调的测试启动类，不打入生产包。
 * HTTP、认证、图节点和 Redis 检查点均真实运行，只有模型响应使用确定性场景。
 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
        "com.github.yulichang.autoconfigure.MybatisPlusJoinAutoConfiguration",
        "org.redisson.spring.starter.RedissonAutoConfigurationV2"
})
@Import({GraphController.class, GraphServiceImpl.class, RunProgressPublisher.class,
        ConversationExecutionLock.class, VisibleMessageCodec.class,
        ConversationMessageStore.class, NodeMemoryRecorder.class, ConversationThreads.class,
        RunStartPreparer.class, RunResumePreparer.class, RunFailureHandler.class,
        RunCoordinator.class, RunStateResolver.class,
        SaTokenConfig.class, SaTokenExceptionHandler.class,
        JacksonConfig.class, GraphUiIntegrationApplication.TestEndpoints.class,
        GraphUiIntegrationApplication.TestExceptionAdvice.class})
public class GraphUiIntegrationApplication {

    public static void main(String[] args) {
        // 保留旧入口，实际统一采用真实数据库版本，避免联调与生产持久化行为分叉。
        GraphUiDatabaseIntegrationApplication.main(args);
    }

    @Bean(destroyMethod = "shutdown")
    RedissonClient testRedis(@Value("${spring.data.redis.host}") String host,
                             @Value("${spring.data.redis.port}") int port,
                             @Value("${spring.data.redis.database}") int database) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4)
                .setConnectTimeout(2000)
                .setTimeout(3000)
                .setRetryAttempts(0);
        return Redisson.create(config);
    }

    @Bean
    RedisConnectionFactory testRedisConnectionFactory(RedissonClient redis) {
        // 隔离 classpath 只有 Redisson；显式复用同一连接，供 Sa-Token 的 RedisTemplate 会话使用。
        return new RedissonConnectionFactory(redis);
    }

    @Bean
    RedisSaver testSaver(RedissonClient redis) {
        return new RedisSaverConfiguration().graphRedisSaver(redis);
    }

    /**
     * 联调夹具不连数据库，会话与消息落库用桩替代，图与检查点仍然真实运行。
     */
    @Bean
    AiConversationMapper testConversationMapper() {
        AiConversationMapper mapper = mock(AiConversationMapper.class);
        when(mapper.selectOwned(anyLong(), anyLong()))
                .thenReturn(new AiConversation().setUserId(1L).setTitle("联调会话"));
        return mapper;
    }

    /**
     * 桩消息表：只保证序号从 1 开始递增，落库动作不产生真实写入。
     */
    @Bean
    AiMessageMapper testMessageMapper() {
        AiMessageMapper mapper = mock(AiMessageMapper.class);
        AtomicLong seq = new AtomicLong();
        when(mapper.selectVisibleMaxSeq(anyLong(), anyLong())).thenAnswer(invocation -> seq.getAndIncrement());
        return mapper;
    }

    /**
     * 节点记忆同样只在真实库里写入，夹具用空桩避免依赖数据源。
     */
    @Bean
    NodeMemoryStore testNodeMemoryStore() {
        return new NodeMemoryStore(mock(AiNodeMemoryMapper.class), mock(AiNodeWorkingMemoryMapper.class));
    }

    @Bean
    CompiledGraph testGraph(RedisSaver saver, ObjectMapper mapper, Validator validator,
                           NodeMemoryStore nodeMemoryStore) throws Exception {
        String plannerPrompt = new ClassPathResource("prompts/planner.md").getContentAsString(StandardCharsets.UTF_8);
        String reviewPrompt = new ClassPathResource("prompts/need_review.md").getContentAsString(StandardCharsets.UTF_8);
        // 记忆写入协议收拢在 Recorder；夹具的 Store 用空桩吞写，不连数据源
        NodeMemoryRecorder nodeMemoryRecorder = new NodeMemoryRecorder(nodeMemoryStore, mapper);
        PlannerNode planner = new PlannerNode(ChatClient.create(new ScenarioModel(mapper)),
                plannerPrompt, Clock.systemDefaultZone(), nodeMemoryRecorder);
        // 消息落库与真实 Store 同形状：映射器由桩吞写，不连数据库也能完整走一次发布拍
        AiConversationMapper publishConversations = mock(AiConversationMapper.class);
        when(publishConversations.touchLastMessageAt(anyLong(), any())).thenReturn(1);
        PlatformTransactionManager publishTx = mock(PlatformTransactionManager.class);
        when(publishTx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ConversationMessageStore publishStore = new ConversationMessageStore(mock(AiMessageMapper.class),
                publishConversations, new VisibleMessageCodec(mapper), new TransactionTemplate(publishTx));
        NeedCheckNode needCheck = new NeedCheckNode(mapper, validator, 2, 3, 6,
                nodeMemoryRecorder, publishStore, new ConversationThreads(), new VisibleResultFactory());
        NeedReviewNode needReview = new NeedReviewNode(ChatClient.create(new ScenarioModel(mapper)),
                reviewPrompt, mapper, 2, 3, nodeMemoryRecorder, publishStore, new ConversationThreads(),
                new VisibleResultFactory(), Clock.systemDefaultZone());
        NeedSelectorNode needSelector = new NeedSelectorNode(mapper);
        return GraphTopology.compile(planner, needCheck, needReview, needSelector,
                new DataAccessNode(new StubSchemaProvider()),
                new KnowledgeNode(new AuthorizedKnowledgeProvider()), saver, 2);
    }

    /** 模型场景仅观察本任务提示词，不用全局调用次数影响其他任务。 */
    static final class ScenarioModel implements ChatModel {

        private final ObjectMapper mapper;

        ScenarioModel(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            String input = prompt.getInstructions().stream()
                    .filter(message -> message.getMessageType() == MessageType.USER)
                    .map(message -> message.getText() == null ? "" : message.getText())
                    .reduce("", (left, right) -> left + right);
            // 审阅节点与规划节点共用一个模型桩：按输入里的标记区分
            if (input.contains("待审需求数组")) {
                return respond(Map.of("verdict", "OK", "issues", List.of()));
            }
            String query = section(input, "最初需求：\n", "当前任务的澄清问答").trim();
            String history = section(input, "当前任务的澄清问答（按时间从早到晚）：\n", "\n本次为输出格式修复").trim();
            List<String> answers = readableAnswers(history);
            Map<String, Object> output;
            // 需求中已经明确仓库、周期且只是销量统计时，直接验证无 Human 的出口。
            boolean completeQuery = query.contains("统计") && query.contains("仓库")
                    && (query.contains("最近30天") || query.contains("本月") || query.contains("上月"));
            if (completeQuery || answers.size() >= 2) {
                String confirmed = String.join("；", answers);
                String context = "原始需求：" + query;
                if (!confirmed.isBlank()) {
                    context += "；已确认条件：" + confirmed;
                }
                output = Map.of(
                        "needs", List.of(need("N1", "DATA",
                                completeQuery ? "按指定仓库和统计周期分析商品销量"
                                        : "结合已确认的范围和约束，分析库存与销量并形成补货建议",
                                completeQuery
                                        ? "按已确认范围和周期汇总商品销量，不预设查询结果。" + context
                                        : "结合已确认范围和周期汇总库存与销量，不预设查询结果。" + context)),
                        "needs_clarification", false,
                        "questions", List.of());
            } else if (answers.size() == 1) {
                output = clarification(List.of(Map.of(
                        "id", "goal",
                        "question", "是否有需要优先考虑的补货目标或限制？",
                        "options", List.of())));
            } else {
                output = clarification(List.of(
                        Map.of("id", "period", "question", "本次使用哪个统计周期？",
                                "options", List.of("最近30天", "本月", "上月")),
                        Map.of("id", "scope", "question", "需要分析哪些仓库或商品范围？",
                                "options", List.of())));
            }
            return respond(output);
        }

        /**
         * 造一条需求：一项 SQL 证据项。
         */
        private Map<String, Object> need(String id, String intent, String need, String instruction) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id + "-E1");
            item.put("label", "分析数据");
            item.put("instruction", instruction);
            item.put("source", "SQL");
            item.put("depends", List.of());
            Map<String, Object> needMap = new LinkedHashMap<>();
            needMap.put("id", id);
            needMap.put("intent", intent);
            needMap.put("need", need);
            needMap.put("depends", List.of());
            needMap.put("condition", null);
            needMap.put("evidence_plan", List.of(item));
            return needMap;
        }

        private Map<String, Object> clarification(List<Map<String, Object>> questions) {
            return Map.of(
                    "needs", List.of(),
                    "needs_clarification", true,
                    "questions", questions);
        }

        private ChatResponse respond(Map<String, Object> payload) {
            String output;
            try {
                output = mapper.writeValueAsString(payload);
            } catch (JsonProcessingException error) {
                throw new IllegalStateException("测试场景JSON编码失败", error);
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(output))));
        }

        private String section(String text, String start, String end) {
            int from = text.indexOf(start);
            if (from < 0) {
                return "";
            }
            from += start.length();
            int to = text.indexOf(end, from);
            if (to < 0) {
                return text.substring(from);
            }
            return text.substring(from, to);
        }

        private List<String> readableAnswers(String history) {
            // 仅解析受控测试提示词的回答部分，最终计划不展示 State 或 Java Map 的实现细节。
            Pattern pattern = Pattern.compile(
                    "answer=(.*?)(?=, questions=|\\}, \\{(?:questions|answer)=|\\}\\]\\s*$)",
                    Pattern.DOTALL);
            Matcher matcher = pattern.matcher(history);
            List<String> answers = new ArrayList<>();
            while (matcher.find()) {
                String answer = matcher.group(1).trim().replaceAll("[\\r\\n]+", "；");
                if (!answer.isBlank()) {
                    answers.add(answer);
                }
            }
            return answers;
        }
    }

    /** 仅测试启动类显式导入，生产应用无法扫描到 test 源码中的账号或空工作台。 */
    @RestController
    static class TestEndpoints {

        private static final long TEST_USER_ID = 1970000000000000001L;

        @PostMapping("/auth/login")
        Result<Map<String, Object>> login(@RequestBody LoginRequest request) {
            if (!"graph-test".equals(request.username()) || !"graph-test".equals(request.password())) {
                throw new BizException(ErrorCode.USER_PASSWORD_ERROR);
            }
            LoginUser user = new LoginUser();
            user.setUserId(TEST_USER_ID);
            user.setUsername("graph-test");
            user.setRealName("图规划联调用户");
            user.setDeptId(1L);
            user.setDeptName("本地联调");
            user.setIsAdmin(false);
            user.setRoleCodes(List.of("GRAPH_TEST"));
            user.setPermissionCodes(List.of("ai:assistant:use"));
            user.setLastLoginAt(LocalDateTime.now());
            StpUtil.login(TEST_USER_ID);
            UserContext.setCurrentUser(user);
            return Result.ok(Map.of("tokenName", StpUtil.getTokenName(), "token", StpUtil.getTokenValue(), "user", user));
        }

        @GetMapping("/auth/me")
        Result<LoginUser> currentUser() {
            return Result.ok(UserContext.requireCurrentUser());
        }

        @PostMapping("/auth/logout")
        Result<Void> logout() {
            StpUtil.logout();
            return Result.ok();
        }

        @GetMapping("/dashboard/overview")
        Result<Map<String, Object>> overview() {
            Map<String, Object> empty = Map.of("state", "EMPTY");
            Map<String, Object> access = Map.of(
                    "metrics", Map.of("MONTH_SALES", empty, "MONTH_GROSS_PROFIT", empty,
                            "PENDING_ORDERS", empty, "STOCK_RISK_SKU", empty),
                    "todos", empty,
                    "stockAlerts", empty,
                    "orderStages", empty,
                    "topProducts", empty,
                    "supplierPerformance", empty);
            LocalDate start = LocalDate.now().withDayOfMonth(1);
            Map<String, Object> result = new HashMap<>();
            result.put("refreshedAt", LocalDateTime.now().toString());
            result.put("pendingCount", 0);
            result.put("metrics", List.of());
            result.put("trend", List.of());
            result.put("todos", List.of());
            result.put("stockAlerts", List.of());
            result.put("orderStages", List.of());
            result.put("topProducts", List.of());
            result.put("supplierPerformance", List.of());
            result.put("orderStagePeriod", Map.of("type", "CURRENT_CALENDAR_MONTH",
                    "startAt", start.toString(), "endAtExclusive", start.plusMonths(1).toString()));
            result.put("access", access);
            return Result.ok(result);
        }
    }

    record LoginRequest(
            String username,
            String password) {
    }

    /** 保持本地错误协议可读，同时不引入生产全局处理器依赖的消息队列。 */
    @RestControllerAdvice
    static class TestExceptionAdvice {

        @ExceptionHandler(BizException.class)
        Result<Void> businessError(BizException error) {
            return Result.fail(error.getCode(), error.getMessage());
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        @ResponseStatus(HttpStatus.BAD_REQUEST)
        Result<Void> invalidRequest(MethodArgumentNotValidException error) {
            return Result.fail(ErrorCode.PARAM_ERROR.getCode(), "请求字段不符合校验规则");
        }
    }
}
