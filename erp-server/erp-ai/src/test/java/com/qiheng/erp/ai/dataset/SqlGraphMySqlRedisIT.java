package com.qiheng.erp.ai.dataset;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;
import com.qiheng.erp.ai.sql.graph.SqlGraph;
import com.qiheng.erp.ai.sql.graph.SqlNodeSupport;
import com.qiheng.erp.ai.sql.graph.model.SqlModelClient;
import com.qiheng.erp.ai.sql.graph.node.SqlPrepareNode;
import com.qiheng.erp.ai.sql.graph.node.SqlGenerateNode;
import com.qiheng.erp.ai.sql.graph.node.SqlExecuteNode;
import com.qiheng.erp.ai.sql.graph.node.SqlInspectNode;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.ai.sql.schema.SchemaProvider;
import com.qiheng.erp.ai.sql.execution.ReadOnlySqlExecutor;
import com.qiheng.erp.ai.sql.execution.SqlGuardGapReporter;
import com.qiheng.erp.ai.sql.guard.DruidSqlGuard;
import com.qiheng.erp.ai.knowledge.KnowledgeProvider;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 固定模型响应，连接隔离 MySQL 和 Redis 验证完整 SQL 子图链路。 */
@EnabledIfEnvironmentVariable(named = "ERP_SQL_GRAPH_REDIS_TEST", matches = "true")
class SqlGraphMySqlRedisIT {
    @TempDir Path directory;

    @Test
    void realQueryIsPublishedAndSessionStateIsReused() throws Exception {
        String url = System.getenv("DATASET_IT_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "需要隔离 DATASET_IT_URL");
        String username = System.getenv("DATASET_IT_USER");
        String password = System.getenv("DATASET_IT_PASSWORD");
        String readOnlyUser = System.getenv("DATASET_IT_RO_USER");
        String readOnlyPassword = System.getenv("DATASET_IT_RO_PASSWORD");
        if (readOnlyUser == null || readOnlyUser.isBlank()) {
            readOnlyUser = username;
            readOnlyPassword = password;
        }
        String readOnlyUrl = url.matches("(?i).*[?&]allowMultiQueries=.*")
                ? url.replaceAll("(?i)([?&]allowMultiQueries=)[^&]*", "$1false")
                : url + (url.contains("?") ? "&" : "?") + "allowMultiQueries=false";
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        jdbc.execute(Files.readString(Path.of("..", "..", "docs", "database", "sql", "029_ai_dataset.sql")));
        DatasetMySqlIT.ensureDescriptionColumn(jdbc);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String table = "ai_stock_it_" + suffix;
        String thread = "sql-e2e-" + suffix;
        String firstRun = "e2e_" + suffix + "_1";
        String secondRun = "e2e_" + suffix + "_2";
        String thirdRun = "e2e_" + suffix + "_3";
        String partialRun = "e2e_" + suffix + "_4";
        String afterRun = "e2e_" + suffix + "_5";
        String sql1 = "SELECT id, quantity FROM " + table + " WHERE warehouse_id = 1 ORDER BY id";
        String sql2 = "SELECT id, quantity FROM " + table + " WHERE warehouse_id = 2 ORDER BY id";
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(config);
        ReadOnlySqlExecutor executor = null;
        try {
            jdbc.execute("CREATE TABLE " + table + " (id BIGINT PRIMARY KEY, warehouse_id BIGINT NOT NULL, quantity DECIMAL(16,2) NOT NULL)");
            jdbc.update("INSERT INTO " + table + " (id, warehouse_id, quantity) VALUES (?,?,?),(?,?,?),(?,?,?)",
                    1, 1, new BigDecimal("30.25"), 2, 1, new BigDecimal("8.50"), 3, 2, new BigDecimal("9.75"));
            var saver = RedisSaver.builder().redisson(redis)
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            var columns = List.of(new AuthorizedSchemaSnapshot.Column("id", false, true),
                    new AuthorizedSchemaSnapshot.Column("warehouse_id", false, false),
                    new AuthorizedSchemaSnapshot.Column("quantity", false, false));
            var snapshot = AuthorizedSchemaSnapshot.of("库存表 " + table + "：id、warehouse_id、quantity",
                    List.of(new AuthorizedSchemaSnapshot.Table(table, "测试库存", "warehouse", false, "id")),
                    Map.of(table, columns));
            SchemaProvider schemas = mock(SchemaProvider.class);
            when(schemas.provideFreshSnapshot(any())).thenReturn(snapshot);
            LoginUser user = new LoginUser();
            user.setUserId(1001L);
            user.setPermissionCodes(List.of("ai:assistant:use", "warehouse:query"));
            LoginUserService users = mock(LoginUserService.class);
            when(users.findByUserId(1001L)).thenReturn(user);
            KnowledgeProvider knowledge = permissions -> "quantity 为当前库存";
            var ds = new AiSqlProperties.Datasource(readOnlyUrl, readOnlyUser, readOnlyPassword, "com.mysql.cj.jdbc.Driver",
                    "sql-graph-e2e", 1, 1, 2, 3000, "SELECT 1", 1000);
            var properties = new AiSqlProperties(ds, true, 200, 5000, 10, 10);
            var guard = new DruidSqlGuard();
            ObjectMapper mapper = new ObjectMapper();
            var repository = DatasetMapperTestFactory.create(jdbc.getDataSource(), mapper);
            var access = new DatasetAccessVerifier(repository, users, schemas, guard, properties);
            var datasets = new DatasetManager(repository, access, mapper, directory.toString(), 0);
            executor = new ReadOnlySqlExecutor(properties, mock(SqlGuardGapReporter.class));
            ChatModel model = mock(ChatModel.class);
            when(model.call(any(Prompt.class))).thenReturn(
                    response(mapper.writeValueAsString(Map.of("decision", "QUERY", "needFullData", false, "sql", sql1))),
                    response(pass()),
                    response(mapper.writeValueAsString(Map.of("decision", "QUERY", "needFullData", false, "sql", sql2))),
                    response(pass()));
            var support = new SqlNodeSupport(schemas, knowledge, users, 3, org.mockito.Mockito.mock(com.qiheng.erp.ai.sql.graph.SqlRetryReporter.class));
            var modelClient = new SqlModelClient(model, mapper);
            SqlGraph graph = new SqlGraph(saver, new SqlPrepareNode(support),
                    new SqlGenerateNode(support, modelClient, properties),
                    new SqlExecuteNode(support, guard, executor, properties, datasets, mapper),
                    new SqlInspectNode(support, modelClient, guard, properties, datasets, mapper));
            var first = graph.start(thread, input(firstRun, "查询一号仓库库存"));
            assertThat(first.get("status")).isEqualTo("SUCCESS");
            String firstDataset = (String) first.get("datasetId");
            var expected = jdbc.query(sql1, (rs, row) -> List.of(rs.getLong("id"), rs.getBigDecimal("quantity")));
            var actual = datasets.read(firstDataset, 1001, 2001);
            assertThat(actual).hasSize(expected.size());
            for (int index = 0; index < expected.size(); index++) {
                assertThat(((Number) actual.get(index).get(0)).longValue()).isEqualTo(((Number) expected.get(index).get(0)).longValue());
                assertThat(new BigDecimal(actual.get(index).get(1).toString()))
                        .isEqualByComparingTo((BigDecimal) expected.get(index).get(1));
            }
            graph.clear(thread);
            var second = graph.start(thread, input(secondRun, "查询二号仓库库存"));
            assertThat(second.get("status")).isEqualTo("SUCCESS");
            assertThat(second.get("datasetId")).isNotEqualTo(firstDataset);
            var secondRows = datasets.read((String) second.get("datasetId"), 1001, 2001);
            assertThat(secondRows).hasSize(1);
            assertThat(((Number) secondRows.getFirst().getFirst()).longValue()).isEqualTo(3);
            verify(model, times(4)).call(any(Prompt.class));

            // 使用真实服务、Redis 检查点和 Mapper，检查 RETRY 后旧暂存何时被清理。
            AtomicInteger modelCalls = new AtomicInteger();
            AtomicReference<String> rejected = new AtomicReference<>();
            ChatModel retryModel = mock(ChatModel.class);
            when(retryModel.call(any(Prompt.class))).thenAnswer(invocation -> {
                int call = modelCalls.getAndIncrement();
                if (call == 1) {
                    rejected.set(jdbc.queryForObject("SELECT dataset_id FROM ai_dataset WHERE sql_run_id=? AND attempt=1",
                            String.class, thirdRun));
                    return response("{\"decision\":\"RETRY\",\"message\":\"调整查询\"}");
                }
                if (call == 3) return response(pass());
                return response(mapper.writeValueAsString(Map.of("decision", "QUERY", "needFullData", false, "sql", sql1)));
            });
            var retryClient = new SqlModelClient(retryModel, mapper);
            var retryGraph = new SqlGraph(saver, new SqlPrepareNode(support),
                    new SqlGenerateNode(support, retryClient, properties),
                    new SqlExecuteNode(support, guard, executor, properties, datasets, mapper),
                    new SqlInspectNode(support, retryClient, guard, properties, datasets, mapper));
            var conversations = mock(AiConversationMapper.class);
            when(conversations.selectOwned(2001L, 1001L)).thenReturn(new AiConversation());
            var service = new SqlGraphService(retryGraph, saver, conversations, redis, users, datasets);
            try (var context = mockStatic(UserContext.class)) {
                context.when(UserContext::requireCurrentUser).thenReturn(user);
                var result = service.startForCurrentUser(2001, thirdRun,
                        Map.of("goal", "查询一号仓库库存", "dataRequirement", "FULL"));
                assertThat(result.get("status")).isEqualTo("SUCCESS");
                assertThat(rejected.get()).isNotBlank();
                assertThat(Files.exists(directory.resolve(rejected.get() + ".jsonl"))).isFalse();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_dataset WHERE sql_run_id=? AND status='STAGED'",
                        Integer.class, thirdRun)).isZero();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_dataset WHERE sql_run_id=? AND status='READY' AND attempt=2",
                        Integer.class, thirdRun)).isEqualTo(1);
                assertThat(datasets.read((String) result.get("datasetId"), 1001, 2001)).hasSize(2);
                assertThat(service.recoverForCurrentUser(2001, thirdRun).get("datasetId")).isEqualTo(result.get("datasetId"));
                service.acknowledgeForCurrentUser(2001, thirdRun);

                // 一行上限使真实查询产生截断，验证发布、恢复、读取和确认后的下一次任务。
                var partialDs = new AiSqlProperties.Datasource(readOnlyUrl, readOnlyUser, readOnlyPassword,
                        "com.mysql.cj.jdbc.Driver", "sql-graph-partial-e2e", 1, 1, 2, 3000, "SELECT 1", 1000);
                var smallLimits = new AiSqlProperties(partialDs, true, 1, 1, 2, 10);
                var partialModel = mock(ChatModel.class);
                when(partialModel.call(any(Prompt.class))).thenReturn(
                        response(mapper.writeValueAsString(Map.of("decision", "QUERY", "needFullData", true, "sql", sql1))),
                        response(pass()),
                        response(mapper.writeValueAsString(Map.of("decision", "QUERY", "needFullData", true, "sql", sql2))),
                        response(pass()));
                var partialClient = new SqlModelClient(partialModel, mapper);
                var partialAccess = new DatasetAccessVerifier(repository, users, schemas, guard, smallLimits);
                var partialDatasets = new DatasetManager(repository, partialAccess, mapper, directory.toString(), 0);
                var partialExecutor = new ReadOnlySqlExecutor(smallLimits, mock(SqlGuardGapReporter.class));
                try {
                    var partialGraph = new SqlGraph(saver, new SqlPrepareNode(support),
                            new SqlGenerateNode(support, partialClient, smallLimits),
                            new SqlExecuteNode(support, guard, partialExecutor, smallLimits, partialDatasets, mapper),
                            new SqlInspectNode(support, partialClient, guard, smallLimits, partialDatasets, mapper));
                    var partialService = new SqlGraphService(partialGraph, saver, conversations, redis, users, partialDatasets);
                    var partial = partialService.startForCurrentUser(2001, partialRun,
                            Map.of("goal", "查询一号仓库库存", "dataRequirement", "FULL"));
                    assertThat(partial).containsEntry("status", "PARTIAL").containsEntry("truncated", true)
                            .containsEntry("truncationReason", "ROW_LIMIT").containsEntry("businessFullResult", false);
                    String partialId = (String) partial.get("datasetId");
                    assertThat(partialDatasets.get(partialId, 1001, 2001).truncationReason()).isEqualTo("ROW_LIMIT");
                    var page = partialDatasets.readPage(partialId, 1001, 2001, 0, 5);
                    assertThat(page.rows()).hasSize(1);
                    assertThat(page.datasetTruncated()).isTrue();
                    assertThat(((Map<?, ?>) partial.get("preview")).get("containsAllRows")).isEqualTo(false);
                    assertThat(partialService.recoverForCurrentUser(2001, partialRun))
                            .containsEntry("datasetId", partialId).containsEntry("truncationReason", "ROW_LIMIT");
                    partialService.acknowledgeForCurrentUser(2001, partialRun);
                    var after = partialService.startForCurrentUser(2001, afterRun,
                            Map.of("goal", "查询二号仓库库存", "dataRequirement", "FULL"));
                    assertThat(after).containsEntry("status", "SUCCESS");
                    assertThat(partialDatasets.readPage(partialId, 1001, 2001, 0, 5).datasetTruncated()).isTrue();
                    partialService.acknowledgeForCurrentUser(2001, afterRun);
                } finally {
                    partialExecutor.destroy();
                }
            }
        } finally {
            if (executor != null) executor.destroy();
            jdbc.update("DELETE FROM ai_dataset WHERE sql_run_id IN (?,?,?,?,?)", firstRun, secondRun, thirdRun, partialRun, afterRun);
            jdbc.execute("DROP TABLE " + table);
            for (String testThread : List.of(thread, "sql:1001:2001")) {
                String metaKey = "graph:thread:meta:" + testThread;
                Object internalId = redis.getMap(metaKey).get("thread_id");
                if (internalId != null) {
                    redis.getBucket("graph:checkpoint:content:" + internalId).delete();
                    redis.getMap("graph:thread:reverse:" + internalId).delete();
                }
                redis.getMap(metaKey).delete();
            }
            redis.shutdown();
        }
    }

    private String pass() {
        return """
                {"decision":"PASS","message":"查询结果符合需求","dataDescription":{"content":"库存结果","grain":"每条库存一行","fields":[{"index":0,"name":"id","meaning":"库存编号"},{"index":1,"name":"quantity","meaning":"库存数量"}],"limitations":""}}
                """;
    }

    private Map<String, Object> input(String runId, String goal) {
        return Map.of("userId", 1001L, "conversationId", 2001L,
                "sqlRunId", runId, "attempt", 0, "route", "next",
                "requirement", Map.of("goal", goal, "dataRequirement", "FULL"));
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
