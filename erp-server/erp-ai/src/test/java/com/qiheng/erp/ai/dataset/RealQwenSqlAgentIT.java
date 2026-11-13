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
import com.qiheng.erp.ai.context.AgentToolContext;
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
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.agent.MainAgentService;

/** 隔离数据库、真实模型、工具和两级图的端到端实验。 */
@EnabledIfEnvironmentVariable(named = "ERP_REAL_QWEN_IT", matches = "true")
class RealQwenSqlAgentIT {
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
        // 智能体流水表同样建在隔离库，验证真实模型链路上的流水真的落库。
        jdbc.execute(Files.readString(Path.of("..", "..", "docs", "database", "sql", "035_ai_agent_trace.sql")));
        // 正式建表脚本已包含数据集说明字段，隔离新库无需旧的本地补丁。
        // 重复执行时先清掉上次留下的表与流水，保证实验可重复。
        jdbc.execute("DROP TABLE IF EXISTS ai_stock_probe");
        jdbc.execute("CREATE TABLE ai_stock_probe (id BIGINT PRIMARY KEY, warehouse_id BIGINT NOT NULL, quantity DECIMAL(16,2) NOT NULL, unit_snapshot VARCHAR(32) NOT NULL)");
        jdbc.update("INSERT INTO ai_stock_probe VALUES (1,1,30.25,'千克'),(2,1,8.50,'件'),(3,2,9.75,'千克')");
        Config redisConfig = new Config();
        redisConfig.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(redisConfig);
        ReadOnlySqlExecutor executor = null;
        var traceSession = openTraceSession(jdbc.getDataSource());
        var traceMapper = traceSession.getMapper(AiAgentTraceMapper.class);
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
                    mock(com.qiheng.erp.ai.sql.graph.SqlRetryReporter.class), traceMapper, 3);
            var conversations = mock(AiConversationMapper.class); when(conversations.selectOwned(2001L, 1001L)).thenReturn(new AiConversation());
            var service = new SqlGraphService(graph, saver, conversations, redis, users, datasets);
            var businessGraph = mock(com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider.class);
            when(businessGraph.render()).thenReturn("【ERP 业务系统内实体、字段及关系参考】\n"
                    + "实体：测试库存（ai_stock_probe）\n"
                    + "- id：商品编号\n- warehouse_id：仓库编号\n- quantity：库存数量\n- unit_snapshot：库存单位快照\n");
            var agent = new com.qiheng.erp.ai.config.MainAgentConfiguration().mainAgent(
                    planningModel, saver, new SqlQueryTool(service), new com.qiheng.erp.ai.dataset.tool.ReadDatasetTool(datasets),
                    businessGraph, mock(com.qiheng.erp.ai.clarification.ClarificationService.class), org.mockito.Mockito.mock(com.qiheng.erp.ai.dataset.tool.ListDatasetsTool.class), org.mockito.Mockito.mock(com.qiheng.erp.ai.python.tool.AnalysisResultTool.class), org.mockito.Mockito.mock(com.qiheng.erp.ai.analysis.AnalysisTool.class));
            var messageStore = mock(com.qiheng.erp.ai.conversation.ConversationMessageStore.class);
            var mainService = new MainAgentService(agent, saver, conversations, users,
                    new com.qiheng.erp.ai.conversation.ConversationExecutionLock(redis),
                    messageStore, mapper, traceMapper);
            var config = RunnableConfig.builder().threadId("main:1001:2001").build();
            var outputs = com.qiheng.erp.ai.agent.AgentTestAccess.stream(mainService, 1001L, 2001L,
                    "请查询一号仓库的每个商品库存，返回商品编号、库存数量及对应单位。")
                    .collectList().block(Duration.ofMinutes(3));
            assertThat(outputs).isNotEmpty();
            for (var output : outputs) if (output instanceof StreamingOutput streaming && streaming.chunk() != null) System.out.print(streaming.chunk());
            System.out.println();
            var messages = (List<?>) saver.get(config).orElseThrow().getState().get("messages");
            // 这里只打印隔离库的合成数据和模型输出，不打印模型配置或密钥。
            messages.stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream())
                    .forEach(response -> System.out.println("工具返回 [" + response.name() + "]: " + response.responseData()));
            messages.stream().filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast)
                    .filter(message -> message.getText() != null && !message.getText().isBlank())
                    .reduce((first, last) -> last)
                    .ifPresent(message -> System.out.println("最终模型回复：" + message.getText()));
            var records = jdbc.queryForList("SELECT dataset_id, row_count, status, sql_run_id, source_sql, attempt FROM ai_dataset WHERE user_id=1001 AND conversation_id=2001");
            assertThat(records).hasSize(1);
            assertThat(records.getFirst().get("status")).isEqualTo("READY");
            String datasetId = (String) records.getFirst().get("dataset_id");
            var sqlState = saver.get(RunnableConfig.builder().threadId("sql:1001:2001").build()).orElseThrow().getState();
            assertThat((Map<?, ?>) sqlState.get("sqlWork")).isEmpty();
            System.out.println("SQL 子图最终执行 SQL：" + records.getFirst().get("source_sql"));
            System.out.println("SQL 子图生成尝试次数：" + records.getFirst().get("attempt"));
            System.out.println("SQL 交接后当前工作状态已清空");
            var rows = datasets.read(datasetId, 1001, 2001);
            assertThat(rows).hasSize(2);
            assertThat(datasets.get(datasetId, 1001, 2001).dataDescription()).isNotNull();
            assertThat(rows.stream().flatMap(List::stream).map(String::valueOf).map(value -> {
                try { return new BigDecimal(value); } catch (NumberFormatException error) { return BigDecimal.ZERO; }
            }).filter(value -> value.compareTo(new BigDecimal("30.25")) == 0 || value.compareTo(new BigDecimal("8.50")) == 0).count()).isEqualTo(2);
            // 同一行同时核对数值与单位，避免只验证单位出现在错误商品的记录中。
            assertThat(rows).anySatisfy(row -> {
                assertThat(row.stream().map(String::valueOf)).contains("30.25", "千克");
            }).anySatisfy(row -> {
                assertThat(row.stream().map(String::valueOf)).contains("8.50", "件");
            });
            var calls = messages.stream().filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast)
                    .flatMap(message -> message.getToolCalls().stream()).filter(call -> call.name().equals("query_sql_data")).toList();
            assertThat(calls).isNotEmpty();
            assertThat(messages.stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream()).anyMatch(response ->
                            calls.stream().anyMatch(call -> call.id().equals(response.id()))
                                    && response.responseData().contains(datasetId)
                                    && response.responseData().contains("dataDescription")
                                    && response.responseData().contains("preview"))).isTrue();
            var saved = org.mockito.ArgumentCaptor.forClass(com.qiheng.erp.ai.domain.entity.AiMessage.class);
            org.mockito.Mockito.verify(messageStore, org.mockito.Mockito.atLeastOnce()).append(saved.capture());
            var savedCalls = new java.util.ArrayList<String>();
            var savedResults = new java.util.ArrayList<String>();
            for (var message : saved.getAllValues()) {
                if ("TOOL_CALL".equals(message.getKind())) {
                    savedCalls.add(mapper.readTree(message.getContent()).path("callId").asText());
                }
                if ("TOOL_RESULT".equals(message.getKind())) {
                    savedResults.add(mapper.readTree(message.getContent()).path("callId").asText());
                }
            }
            var actualCalls = messages.stream().filter(AssistantMessage.class::isInstance).map(AssistantMessage.class::cast)
                    .flatMap(message -> message.getToolCalls().stream()).map(AssistantMessage.ToolCall::id).toList();
            var actualResults = messages.stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                    .flatMap(message -> message.getResponses().stream()).map(ToolResponseMessage.ToolResponse::id).toList();
            System.out.println("MAIN_REAL_SHAPE calls=" + actualCalls + " savedCalls=" + savedCalls
                    + " results=" + actualResults + " savedResults=" + savedResults);
            // 普通工具只在消息表留一行展示记录，返回内容只进流水；这里没有追问，因此不应有回答行。
            assertThat(savedResults).isEmpty();
            assertThat(savedCalls).containsExactlyElementsOf(actualCalls);
            System.out.println("真实模型工具调用与 SQL 取数完成，行数=" + rows.size() + "，数据集=" + datasetId);

            // 真实模型跑完后核对流水：三个智能体都要留下原文。
            var traceRows = jdbc.queryForList("SELECT agent_type, event_type, content FROM ai_agent_trace ORDER BY id");
            var byAgent = new java.util.LinkedHashMap<String, java.util.List<String>>();
            for (var row : traceRows) {
                byAgent.computeIfAbsent((String) row.get("agent_type"), ignored -> new java.util.ArrayList<>())
                        .add((String) row.get("event_type"));
            }
            System.out.println("智能体流水分布：" + byAgent);
            assertThat(byAgent).containsKeys("MAIN", "SQL_GENERATE", "SQL_INSPECT");
            assertThat(byAgent.get("MAIN")).contains("INPUT", "MODEL_OUTPUT", "TOOL_CALL", "TOOL_RESULT", "FINAL");
            assertThat(byAgent.get("SQL_GENERATE")).contains("INPUT", "MODEL_OUTPUT");
            assertThat(byAgent.get("SQL_INSPECT")).contains("INPUT", "MODEL_OUTPUT");
            // 生成节点的产出必须带真实 SQL，校验节点的产出必须带检查结论。
            var generateOutput = jdbc.queryForList(
                    "SELECT content FROM ai_agent_trace WHERE agent_type='SQL_GENERATE' AND event_type='MODEL_OUTPUT'");
            assertThat(generateOutput).isNotEmpty();
            assertThat(mapper.readTree((String) generateOutput.getFirst().get("content")).path("sql").asText())
                    .contains("ai_stock_probe");
            // 主智能体的工具调用原文要保留参数，便于回看它到底查了什么。
            var mainCall = jdbc.queryForList(
                    "SELECT content FROM ai_agent_trace WHERE agent_type='MAIN' AND event_type='TOOL_CALL'");
            assertThat(mainCall).isNotEmpty();
            assertThat((String) mainCall.getFirst().get("content")).contains("query_sql_data");
        } finally {
            if (executor != null) executor.destroy();
            traceSession.close();
            redis.shutdown();
        }
    }

    /** 建立指向隔离库的 MyBatis 会话，用于写入智能体流水。 */
    private static org.apache.ibatis.session.SqlSession openTraceSession(javax.sql.DataSource source) throws Exception {
        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("trace-it",
                new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), source));
        configuration.addMapper(AiAgentTraceMapper.class);
        return new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration).openSession(true);
    }
}
