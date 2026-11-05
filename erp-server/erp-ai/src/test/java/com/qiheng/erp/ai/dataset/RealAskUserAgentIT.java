package com.qiheng.erp.ai.dataset;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import com.qiheng.erp.ai.config.OpenAiCompatibleChatModel;
import com.qiheng.erp.ai.config.SqlGraphConfiguration;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import java.math.BigDecimal;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.entity.AiDataTable;
import com.qiheng.erp.ai.domain.entity.AiDataTableColumn;
import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.sql.schema.SchemaProvider;
import com.qiheng.erp.ai.sql.schema.SchemaRenderer;
import com.qiheng.erp.ai.sql.execution.ReadOnlySqlExecutor;
import com.qiheng.erp.ai.sql.execution.SqlGuardGapReporter;
import com.qiheng.erp.ai.sql.guard.DruidSqlGuard;
import com.qiheng.erp.ai.knowledge.KnowledgeProvider;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.ai.sql.tool.SqlToolContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 隔离数据库、真实模型、工具和两级图的端到端实验。 */
@EnabledIfEnvironmentVariable(named = "ERP_REAL_QWEN_IT", matches = "true")
class RealAskUserAgentIT {
    @TempDir Path directory;

    /** 用固定合成库存数据验证真实模型、工具调用和实际 SQL 结果。 */
    @Test
    void invokesSqlToolAndQueriesRealDatabase() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        String key = settings.getProperty("erp.ai.api-key");
        String base = settings.getProperty("erp.ai.base-url");
        String modelName = settings.getProperty("erp.ai.model.flash");
        assertThat(key).isNotBlank();
        assertThat(base).isNotBlank();
        assertThat(modelName).isNotBlank();
        String thinking = settings.getProperty("erp.ai.model.enable-thinking");
        Boolean enableThinking = thinking == null || thinking.isBlank() ? null : Boolean.valueOf(thinking.trim());
        var mainModel = OpenAiCompatibleChatModel.create(key, base, modelName, 0.2,
                Duration.ofSeconds(90), enableThinking);
        var planningModel = OpenAiCompatibleChatModel.create(key, base,
                settings.getProperty("erp.ai.model.pro"), 0.2, Duration.ofSeconds(90), enableThinking);
        String url = "jdbc:mysql://127.0.0.1:13327/erp_ai_test?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=false";
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", "isolated_test_password"));
        jdbc.execute(Files.readString(Path.of("..", "..", "docs", "database", "sql", "029_ai_dataset.sql")));
        jdbc.execute(Files.readString(Path.of("..", "sql", "030_ai_dataset_description.sql")));
        jdbc.execute("CREATE TABLE ai_stock_probe (id BIGINT PRIMARY KEY, warehouse_id BIGINT NOT NULL, quantity DECIMAL(16,2) NOT NULL, unit_snapshot VARCHAR(32) NOT NULL)");
        jdbc.update("INSERT INTO ai_stock_probe VALUES (1,1,30.25,'千克'),(2,1,8.50,'件'),(3,2,9.75,'千克'),(4,3,99.00,'件')");
        Config redisConfig = new Config();
        redisConfig.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(redisConfig);
        ReadOnlySqlExecutor executor = null;
        try {
            var saver = RedisSaver.builder().redisson(redis).stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            var columns = List.of(new AuthorizedSchemaSnapshot.Column("id", false, true), new AuthorizedSchemaSnapshot.Column("warehouse_id", false, false), new AuthorizedSchemaSnapshot.Column("quantity", false, false), new AuthorizedSchemaSnapshot.Column("unit_snapshot", false, false));
            var stockTable = new AiDataTable();
            stockTable.setTableName("ai_stock_probe");
            stockTable.setBusinessName("测试库存");
            var id = new AiDataTableColumn();
            id.setColumnName("id");
            id.setColumnType("BIGINT");
            id.setBusinessName("商品编号");
            id.setPrimaryKey(1);
            var warehouseId = new AiDataTableColumn();
            warehouseId.setColumnName("warehouse_id");
            warehouseId.setColumnType("BIGINT");
            warehouseId.setBusinessName("仓库编号");
            var quantity = new AiDataTableColumn();
            quantity.setColumnName("quantity");
            quantity.setColumnType("DECIMAL(16,2)");
            quantity.setBusinessName("库存数量");
            var unit = new AiDataTableColumn();
            unit.setColumnName("unit_snapshot");
            unit.setColumnType("VARCHAR(32)");
            unit.setBusinessName("库存数量单位快照");
            String schemaText = new SchemaRenderer().render(List.of(stockTable),
                    Map.of("ai_stock_probe", List.of(id, warehouseId, quantity, unit)), List.of(), Set.of());
            var snapshot = AuthorizedSchemaSnapshot.of(schemaText, List.of(new AuthorizedSchemaSnapshot.Table("ai_stock_probe", "测试库存", "warehouse", false, "id")), Map.of("ai_stock_probe", columns));
            SchemaProvider schemas = mock(SchemaProvider.class);
            when(schemas.provideFreshSnapshot(any())).thenReturn(snapshot);
            LoginUser user = new LoginUser(); user.setUserId(1001L); user.setPermissionCodes(List.of("ai:assistant:use", "warehouse:query"));
            LoginUserService users = mock(LoginUserService.class); when(users.findByUserId(1001L)).thenReturn(user);
            KnowledgeProvider knowledge = permissions -> "quantity 为库存数量；unit_snapshot 为该库存数量对应的单位快照；warehouse_id 为仓库编号。不同单位不可直接合计。";
            var ds = new AiSqlProperties.Datasource(url, "test_reader", "test_reader_password", "com.mysql.cj.jdbc.Driver", "real-model-it", 1, 1, 2, 3000, "SELECT 1", 1000);
            var properties = new AiSqlProperties(ds, true, 200, 5000, 10, 10);
            var guard = new DruidSqlGuard(); var mapper = new ObjectMapper();
            var repository = DatasetMapperTestFactory.create(jdbc.getDataSource(), mapper);
            var datasets = new DatasetManager(repository, new DatasetAccessVerifier(repository, users, schemas, guard, properties), mapper, directory.toString(), 0);
            executor = new ReadOnlySqlExecutor(properties, mock(SqlGuardGapReporter.class));
            var graph = new SqlGraphConfiguration().sqlGraph(saver, schemas, knowledge, mainModel,
                    mapper, guard, executor, properties, users, datasets,
                    mock(com.qiheng.erp.ai.sql.graph.SqlRetryReporter.class), 3);
            var conversations = mock(AiConversationMapper.class); when(conversations.selectOwned(2013L, 1001L)).thenReturn(new AiConversation());
            var service = new SqlGraphService(graph, saver, conversations, redis, users, datasets);
            var businessGraph = mock(com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider.class);
            when(businessGraph.render()).thenReturn("【ERP 业务系统内实体、字段及关系参考】\n"
                    + "实体：测试库存（ai_stock_probe）\n"
                    + "- id：商品编号\n- warehouse_id：仓库编号\n- quantity：库存数量\n- unit_snapshot：库存单位快照\n");
            var questionService = new com.qiheng.erp.ai.clarification.ClarificationService(conversations, users, mapper);
            var agent = new com.qiheng.erp.ai.config.MainAgentConfiguration().mainAgent(
                    planningModel, saver, new SqlQueryTool(service), new com.qiheng.erp.ai.dataset.tool.ReadDatasetTool(datasets),
                    businessGraph, questionService);
            var locks = new com.qiheng.erp.ai.conversation.ConversationExecutionLock(redis);
            var mainService = new com.qiheng.erp.ai.agent.MainAgentService(agent, saver, conversations, users, locks, mock(com.qiheng.erp.ai.conversation.ConversationMessageStore.class), new com.fasterxml.jackson.databind.ObjectMapper());
            var config = RunnableConfig.builder().threadId("main:1001:2013").build();
            var questionCount = new java.util.concurrent.atomic.AtomicInteger();
            var completed = com.qiheng.erp.ai.agent.AgentTestAccess.stream(mainService, 1001L, 2013L,
                    "我要查询多个指定仓库的每个商品库存、数量和单位，目前还没选择仓库；请先查可用仓库并以多选方式追问，不要直接查询全部仓库。")
                    .doOnNext(event -> {
                        if (!"ask_user".equals(event.node())) return;
                        questionCount.incrementAndGet();
                        String requestId = (String) event.state().data().get("questionRequestId");
                        @SuppressWarnings("unchecked")
                        var questions = (List<com.qiheng.erp.ai.clarification.AskUserTool.Question>) event.state().data().get("questions");
                        System.out.println("真实模型追问=" + questions);
                        assertThat(questions).isNotEmpty();
                        var answers = new java.util.ArrayList<com.qiheng.erp.ai.clarification.ClarificationAnswer>();
                        for (var q : questions) {
                            assertThat(q.type()).isEqualTo(com.qiheng.erp.ai.clarification.AskUserTool.QuestionType.MULTIPLE_CHOICE);
                            assertThat(q.options().size()).isGreaterThanOrEqualTo(2);
                            var selected = q.options().stream().filter(option -> option.contains("1") || option.contains("2")).toList();
                            assertThat(selected).hasSize(2);
                            answers.add(new com.qiheng.erp.ai.clarification.ClarificationAnswer(q.id(), selected, ""));
                        }
                        System.out.println("用户选择或补充=" + answers);
                        com.qiheng.erp.ai.agent.AgentTestAccess.answer(questionService, 1001L, 2013L, requestId, answers, null);
                    }).collectList().block(Duration.ofMinutes(3));
            assertThat(completed).isNotEmpty();
            assertThat(questionCount.get()).isGreaterThan(0);
            var messages = (List<?>) saver.get(config).orElseThrow().getState().get("messages");
            messages.stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .forEach(response -> System.out.println("工具返回 [" + response.name() + "]: " + response.responseData()));
            messages.stream().filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast)
                    .filter(message -> message.getText() != null && !message.getText().isBlank())
                    .reduce((a1, a2) -> a2).ifPresent(message -> System.out.println("最终回复=" + message.getText()));
            var records = jdbc.queryForList("SELECT dataset_id, row_count, status, source_sql, attempt FROM ai_dataset WHERE user_id=1001 AND conversation_id=2013");
            System.out.println("数据集记录=" + records);
            // 主智能体可能先查仓库选项；最终结果应包含两个已选择仓库的库存。
            assertThat(records).isNotEmpty();
            assertThat(records).anySatisfy(record -> {
                var rows = datasets.read((String) record.get("dataset_id"), 1001, 2013);
                assertThat(rows).hasSize(3);
                assertThat(rows.toString()).contains("30.25", "8.50", "9.75").doesNotContain("99.00");
            });
        } finally {
            if (executor != null) executor.destroy();
            redis.shutdown();
        }
    }
}
