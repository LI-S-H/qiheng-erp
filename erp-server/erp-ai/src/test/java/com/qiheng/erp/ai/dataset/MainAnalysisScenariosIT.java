package com.qiheng.erp.ai.dataset;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.qiheng.erp.ai.agent.TrustedToolContextInterceptor;
import com.qiheng.erp.ai.clarification.ClarificationService;
import com.qiheng.erp.ai.config.MainAgentConfiguration;
import com.qiheng.erp.ai.analysis.AnalysisTool;
import com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.agent.interceptor.ToolJsonInterceptor;
import com.qiheng.erp.ai.analysis.AnalysisAgentService;
import com.qiheng.erp.ai.config.OpenAiCompatibleChatModel;
import com.qiheng.erp.ai.config.SqlGraphConfiguration;
import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;
import com.qiheng.erp.ai.knowledge.KnowledgeProvider;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.mapper.AiAnalysisResultMapper;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import com.qiheng.erp.ai.python.sandbox.PythonSandboxRuntime;
import com.qiheng.erp.ai.python.tool.PythonAnalysisTool;
import com.qiheng.erp.ai.python.tool.PythonDatasetPreparer;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.dataset.tool.ListDatasetsTool;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import com.qiheng.erp.ai.sql.execution.ReadOnlySqlExecutor;
import com.qiheng.erp.ai.sql.execution.SqlGuardGapReporter;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.qiheng.erp.ai.sql.graph.SqlRetryReporter;
import com.qiheng.erp.ai.sql.guard.DruidSqlGuard;
import com.qiheng.erp.ai.sql.schema.SchemaProvider;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;

/** 在隔离数据库和真实沙箱验证分析智能体自行取数与计算。 */
@EnabledIfEnvironmentVariable(named = "ERP_MAIN_SCENARIOS_IT", matches = "true")
class MainAnalysisScenariosIT {
    @TempDir Path directory;

    /** 使用真实模型生成 SQL，再将结果数据集交给 Python。 */
    @Test
    void exercisesMultipleMainAgentScenarios() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        String thinking = settings.getProperty("erp.ai.model.enable-thinking");
        Boolean enabled = thinking == null || thinking.isBlank() ? null : Boolean.valueOf(thinking.trim());
        var flash = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.flash"), 0.2,
                Duration.ofSeconds(90), enabled);
        var pro = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.pro"), 0.2,
                Duration.ofSeconds(90), enabled);
        String url = "jdbc:mysql://127.0.0.1:13327/erp_ai_test?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=false";
        var source = new DriverManagerDataSource(url, "root", "isolated_test_password");
        var jdbc = new JdbcTemplate(source);
        jdbc.execute(Files.readString(Path.of("..", "..", "docs", "database", "sql", "029_ai_dataset.sql")));
        jdbc.execute(Files.readString(Path.of("..", "..", "docs", "database", "sql", "034_ai_analysis_result.sql")));
        jdbc.execute("CREATE TABLE ai_stock_analysis_probe (id BIGINT PRIMARY KEY, warehouse_id BIGINT NOT NULL, product_id BIGINT NOT NULL, quantity DECIMAL(16,2) NOT NULL, unit_snapshot VARCHAR(32) NOT NULL)");
        jdbc.execute("CREATE TABLE ai_stock_threshold_probe (id BIGINT PRIMARY KEY, warehouse_id BIGINT NOT NULL, product_id BIGINT NOT NULL, safe_qty DECIMAL(16,2) NOT NULL, unit_snapshot VARCHAR(32) NOT NULL)");
        jdbc.update("INSERT INTO ai_stock_analysis_probe VALUES (1,1,101,30,'件'),(2,1,102,8,'箱'),(3,2,101,9.75,'件'),(4,2,102,0,'箱'),(5,2,103,5,'卷')");
        jdbc.update("INSERT INTO ai_stock_threshold_probe VALUES (1,1,101,40,'件'),(2,1,102,6,'箱'),(3,2,101,15,'件'),(4,2,102,4,'箱')");

        var config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(config);
        var mapper = new ObjectMapper();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("io.agentscope.runtime.sandbox.manager.SandboxService");
        var previousLevel = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        var runtime = new PythonSandboxRuntime(mapper, "erp-ai-sandbox-probe:local");
        ReadOnlySqlExecutor executor = null;
        try {
            var saver = RedisSaver.builder().redisson(redis)
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            var columns = List.of(new AuthorizedSchemaSnapshot.Column("id", false, true),
                    new AuthorizedSchemaSnapshot.Column("warehouse_id", false, false),
                    new AuthorizedSchemaSnapshot.Column("product_id", false, false),
                    new AuthorizedSchemaSnapshot.Column("quantity", false, false),
                    new AuthorizedSchemaSnapshot.Column("unit_snapshot", false, false));
            var thresholds = List.of(new AuthorizedSchemaSnapshot.Column("id", false, true),
                    new AuthorizedSchemaSnapshot.Column("warehouse_id", false, false),
                    new AuthorizedSchemaSnapshot.Column("product_id", false, false),
                    new AuthorizedSchemaSnapshot.Column("safe_qty", false, false),
                    new AuthorizedSchemaSnapshot.Column("unit_snapshot", false, false));
            String schema = "表 ai_stock_analysis_probe：id 主键，warehouse_id 仓库编号，product_id 商品编号，quantity 库存数量，unit_snapshot 单位；"
                    + "表 ai_stock_threshold_probe：id 主键，warehouse_id 仓库编号，product_id 商品编号，safe_qty 安全库存，unit_snapshot 单位。"
                    + "两表均无软删除，每仓库每商品最多一行，按 warehouse_id 和 product_id 关联；安全库存可能缺失。";
            var snapshot = AuthorizedSchemaSnapshot.of(schema,
                    List.of(new AuthorizedSchemaSnapshot.Table("ai_stock_analysis_probe", "测试库存", "warehouse", false, "id"),
                            new AuthorizedSchemaSnapshot.Table("ai_stock_threshold_probe", "测试安全库存", "warehouse", false, "id")),
                    Map.of("ai_stock_analysis_probe", columns, "ai_stock_threshold_probe", thresholds));
            SchemaProvider schemas = mock(SchemaProvider.class);
            when(schemas.provideFreshSnapshot(any())).thenReturn(snapshot);
            LoginUser user = new LoginUser();
            user.setUserId(1001L);
            user.setPermissionCodes(List.of("ai:assistant:use", "warehouse:query"));
            LoginUserService users = mock(LoginUserService.class);
            when(users.findByUserId(1001L)).thenReturn(user);
            KnowledgeProvider knowledge = permissions -> "库存与安全库存按仓库和商品对应比较，单位必须一致；缺少安全库存时不能按零处理；不同商品、不同单位不能直接合计。";
            var ds = new AiSqlProperties.Datasource(url, "test_reader", "test_reader_password", "com.mysql.cj.jdbc.Driver", "analysis-sql-it", 1, 1, 2, 3000, "SELECT 1", 1000);
            var properties = new AiSqlProperties(ds, true, 200, 2000, 10, 10);
            var guard = new DruidSqlGuard();
            var repository = DatasetMapperTestFactory.create(source, mapper);
            var datasets = new DatasetManager(repository, new DatasetAccessVerifier(repository, users, schemas, guard, properties), mapper, directory.resolve("datasets").toString(), 0);
            executor = new ReadOnlySqlExecutor(properties, mock(SqlGuardGapReporter.class));
            var graph = new SqlGraphConfiguration().sqlGraph(schemas, knowledge, flash, mapper, guard,
                    executor, properties, users, datasets, mock(SqlRetryReporter.class), mock(AiAgentTraceMapper.class), 3);
            var conversations = mock(AiConversationMapper.class);
            when(conversations.selectOwned(2001L, 1001L)).thenReturn(new AiConversation());
            when(conversations.selectOwned(2002L, 1001L)).thenReturn(new AiConversation());
            when(conversations.selectOwned(2003L, 1001L)).thenReturn(new AiConversation());
            when(conversations.selectOwned(2004L, 1001L)).thenReturn(new AiConversation());
            var sql = new SqlQueryTool(new SqlGraphService(graph, conversations, datasets));
            var configuration = new MybatisConfiguration();
            configuration.addMapper(AiAnalysisResultMapper.class);
            var bean = new MybatisSqlSessionFactoryBean();
            bean.setDataSource(source);
            bean.setConfiguration(configuration);
            var resultMapper = new SqlSessionTemplate(bean.getObject()).getMapper(AiAnalysisResultMapper.class);
            var results = new AnalysisResultStore(resultMapper, datasets, mapper, directory.resolve("results").toString(), 0);
            var pythonTool = new PythonAnalysisTool(new PythonDatasetPreparer(datasets), results);
            var traces = mock(AiAgentTraceMapper.class);
            var businessGraph = mock(BusinessGraphProvider.class);
            when(businessGraph.render()).thenReturn("【ERP 业务系统内实体、字段及关系参考】仓库与库存按仓库编号关联；查询以授权数据为准。");
            String prompt = new ClassPathResource("prompts/analysis-agent.md").getContentAsString(StandardCharsets.UTF_8);
            var agent = new AnalysisAgentService(pro, pythonTool, runtime, prompt, traces, mapper, sql,
                    new ReadDatasetTool(datasets), new ToolJsonInterceptor(mapper),
                    new ListDatasetsTool(datasets), new AnalysisResultTool(results), datasets, results, null,
                    new com.qiheng.erp.ai.agent.interceptor.BusinessGraphInterceptor(businessGraph));
            doAnswer(call -> {
                System.out.println("TRACE " + call.getArgument(4) + " " + call.getArgument(5));
                return null;
            }).when(traces).insert(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString());
            // 预置当前会话可见的两行数据集，让分析智能体的首次输入含已有预览。
            var preseed = datasets.stage(new DatasetRequest("main-e2e-preseed", 1001, 2001,
                    "仓库1库存", "SELECT product_id, quantity FROM ai_stock_analysis_probe WHERE warehouse_id=1", 1),
                    new com.qiheng.erp.ai.sql.execution.model.QueryResult(
                            List.of(new com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn("product_id", "product_id", java.sql.Types.BIGINT, "BIGINT", false),
                                    new com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn("quantity", "quantity", java.sql.Types.DECIMAL, "DECIMAL", false)),
                            List.of(List.of(101, 30), List.of(102, 8)), 2, true, 1, 1, "preseed"));
            datasets.approve(preseed.datasetId(), 1001, 2001,
                    new SqlDataDescription("仓库1库存预览", "每行一个商品",
                            List.of(new SqlDataField(0, "product_id", "商品编号"),
                                    new SqlDataField(1, "quantity", "库存数量")),
                            "预览不含单位和安全库存"));
            var main = new MainAgentConfiguration().mainAgent(pro, saver, sql, new ReadDatasetTool(datasets),
                    businessGraph, mock(ClarificationService.class), new ListDatasetsTool(datasets),
                    new AnalysisResultTool(results), new AnalysisTool(agent));
            var soft = new org.assertj.core.api.SoftAssertions();
            var scenarios = List.of(
                    Map.of("name", "completed", "expected", "COMPLETED", "request",
                            "请交给数据分析智能体使用Python计算：库存数量为8、5、3件，求合计并说明单位。"),
                    Map.of("name", "need_info", "expected", "NEED_INFO", "request",
                            "请交给数据分析智能体分析名为东仓的库存。已知东仓是仓库名称而不是编号；"
                                    + "必须确认名称和编号的映射才能查询，找不到映射时说明需要用户确认，不得猜编号。"),
                    Map.of("name", "partial", "expected", "PARTIAL", "request",
                            "请交给数据分析智能体：先统计仓库编号1的库存记录条数，再统计本月销售订单条数。"
                                    + "销售订单需以授权结构能否查询为准，查不到时报告已完成部分和限制，不要编造。"),
                    Map.of("name", "need_info_delegated", "expected", "NEED_INFO", "request",
                            "请先直接调用 analyzeData，将以下完整任务交给分析智能体，不要先代替它查询："
                                    + "分析名称为东仓的库存，东仓是名称而非编号；必须确认名称和编号映射，"
                                    + "如果没有映射，请告知主智能体缺少什么，让主智能体向用户确认。")
            );
            long conversationId = 2000;
            for (var scenario : scenarios) {
                conversationId++;
                String selected = System.getProperty("erp.test.scenario", "");
                if (!selected.isBlank() && !selected.equals(scenario.get("name"))) {
                    continue;
                }
                var configForRun = RunnableConfig.builder().threadId("scenario-" + java.util.UUID.randomUUID())
                        .addMetadata("businessDate", "2026-11-09")
                        .addMetadata(TrustedToolContextInterceptor.TRUSTED_CONTEXT,
                                new AgentToolContext(1001, conversationId, "2026-11-09")).build();
                System.out.println("SCENARIO_REQUEST " + scenario.get("name") + " " + scenario.get("request"));
                try {
                    var outputs = main.stream(scenario.get("request"), configForRun).collectList().block(Duration.ofMinutes(8));
                    soft.assertThat(outputs).as(scenario.get("name") + " 输出").isNotEmpty();
                    var checkpoint = saver.get(configForRun).orElseThrow();
                    var messages = (List<?>) checkpoint.getState().get("messages");
                    System.out.println("SCENARIO_MESSAGES " + scenario.get("name") + " " + messages);
                    var assistantMessages = messages.stream()
                            .filter(org.springframework.ai.chat.messages.AssistantMessage.class::isInstance)
                            .map(org.springframework.ai.chat.messages.AssistantMessage.class::cast).toList();
                    var toolCalls = assistantMessages.stream().flatMap(message -> message.getToolCalls().stream()).toList();
                    System.out.println("SCENARIO_MAIN_TOOL_CALLS " + scenario.get("name") + " " + toolCalls);
                    var lastAnswer = assistantMessages.stream().filter(message -> message.getToolCalls().isEmpty())
                            .reduce((first, second) -> second);
                    System.out.println("SCENARIO_MAIN_FINAL " + scenario.get("name") + " "
                            + lastAnswer.map(org.springframework.ai.chat.messages.AssistantMessage::getText).orElse("无"));
                    soft.assertThat(lastAnswer).as(scenario.get("name") + " 最终回复").isPresent();
                    var calls = messages.stream().filter(ToolResponseMessage.class::isInstance)
                            .map(ToolResponseMessage.class::cast).flatMap(m -> m.getResponses().stream())
                            .filter(item -> "analyzeData".equals(item.name())).toList();
                    boolean directClarification = "need_info".equals(scenario.get("name")) && calls.isEmpty()
                            && toolCalls.stream().anyMatch(call -> "ask_user".equals(call.name()));
                    if (directClarification) {
                        System.out.println("SCENARIO_PATH need_info 主智能体先确认条件，未调用分析智能体");
                    } else {
                        soft.assertThat(calls).as(scenario.get("name") + " 分析调用").hasSize(1);
                    }
                    if (calls.size() == 1) {
                        var payload = mapper.readTree(calls.getFirst().responseData());
                        System.out.println("SCENARIO_RESULT " + scenario.get("name") + " " + payload);
                        soft.assertThat(payload.path("completion").asText()).as(scenario.get("name") + " 完成状态")
                                .isEqualTo(scenario.get("expected"));
                        soft.assertThat(payload.path("summary").asText()).as(scenario.get("name") + " 结论").isNotBlank();
                        soft.assertThat(payload.has("status")).as(scenario.get("name") + " 旧状态字段").isFalse();
                        if ("partial".equals(scenario.get("name"))) {
                            soft.assertThat(payload.path("summary").asText()).as("部分完成的已知结果和缺口")
                                    .contains("库存").containsAnyOf("销售", "权限");
                        }
                        if (scenario.get("name").startsWith("need_info")) {
                            soft.assertThat(toolCalls.stream().anyMatch(call -> "ask_user".equals(call.name())))
                                    .as("缺失必要条件时是否发起追问").isTrue();
                        }
                    }
                } catch (Exception error) {
                    System.out.println("SCENARIO_ERROR " + scenario.get("name") + " " + error);
                    soft.fail(scenario.get("name") + " 执行异常: " + error.getClass().getSimpleName());
                }
            }
            soft.assertAll();

        } finally {
            if (executor != null) {
                executor.destroy();
            }
            runtime.close();
            redis.shutdown();
            logger.setLevel(previousLevel);
        }
    }
}
