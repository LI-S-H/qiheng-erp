package com.qiheng.erp.ai.dataset;

import com.alibaba.cloud.ai.graph.OverAllState;
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
@EnabledIfEnvironmentVariable(named = "ERP_ANALYSIS_SQL_IT", matches = "true")
class AnalysisSqlPythonIT {
    @TempDir Path directory;

    /** 使用真实模型生成 SQL，再将结果数据集交给 Python。 */
    @Test
    void queriesAndComputesWithRealTools() throws Exception {
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
        jdbc.execute("CREATE TABLE ai_stock_analysis_probe (id BIGINT PRIMARY KEY, warehouse_id BIGINT NOT NULL, quantity DECIMAL(16,2) NOT NULL, unit_snapshot VARCHAR(32) NOT NULL)");
        jdbc.update("INSERT INTO ai_stock_analysis_probe VALUES (1,1,30.25,'件'),(2,1,8.50,'件'),(3,2,9.75,'件')");

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
                    new AuthorizedSchemaSnapshot.Column("quantity", false, false),
                    new AuthorizedSchemaSnapshot.Column("unit_snapshot", false, false));
            String schema = "表 ai_stock_analysis_probe：id 商品编号，warehouse_id 仓库编号，quantity 库存数量，unit_snapshot 单位；主键 id，无软删除。";
            var snapshot = AuthorizedSchemaSnapshot.of(schema,
                    List.of(new AuthorizedSchemaSnapshot.Table("ai_stock_analysis_probe", "测试库存", "warehouse", false, "id")),
                    Map.of("ai_stock_analysis_probe", columns));
            SchemaProvider schemas = mock(SchemaProvider.class);
            when(schemas.provideFreshSnapshot(any())).thenReturn(snapshot);
            LoginUser user = new LoginUser();
            user.setUserId(1001L);
            user.setPermissionCodes(List.of("ai:assistant:use", "warehouse:query"));
            LoginUserService users = mock(LoginUserService.class);
            when(users.findByUserId(1001L)).thenReturn(user);
            KnowledgeProvider knowledge = permissions -> "quantity 为库存数量，unit_snapshot 为单位快照；仅同单位可以合计。";
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
            String prompt = new ClassPathResource("prompts/analysis-agent.md").getContentAsString(StandardCharsets.UTF_8);
            var agent = new AnalysisAgentService(pro, pythonTool, runtime, prompt, traces, mapper, sql,
                    new ReadDatasetTool(datasets), new ToolJsonInterceptor(mapper),
                    new ListDatasetsTool(datasets), new AnalysisResultTool(results), datasets, results, null);
            String answer = agent.analyze("查询 1 号仓库各商品库存明细和单位，使用 Python 计算这些同单位库存的总和并报告数值。", new AgentToolContext(1001, 2001));
            System.out.println("ANALYSIS_SQL_PYTHON_RESULT=" + answer);
            assertThat(answer).contains("38.75");
            verify(traces, atLeastOnce()).insert(eq(1001L), eq(2001L), anyString(), eq("ANALYSIS"), eq("TOOL_CALL"), contains("query_sql_data"));
            verify(traces, atLeastOnce()).insert(eq(1001L), eq(2001L), anyString(), eq("ANALYSIS"), eq("TOOL_CALL"), contains("executePython"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_dataset WHERE user_id=1001 AND conversation_id=2001 AND status='READY'", Integer.class)).isGreaterThan(0);
            String datasetId = jdbc.queryForObject("SELECT dataset_id FROM ai_dataset WHERE user_id=1001 AND conversation_id=2001 AND status='READY' ORDER BY created_at DESC LIMIT 1", String.class);
            verify(traces, atLeastOnce()).insert(eq(1001L), eq(2001L), anyString(), eq("ANALYSIS"), eq("TOOL_CALL"),
                    argThat(content -> content.contains("prepareAnalysisData") && content.contains(datasetId)));
            verify(traces, atLeastOnce()).insert(eq(1001L), eq(2001L), anyString(), eq("ANALYSIS"), eq("TOOL_CALL"),
                    argThat(content -> content.contains("executePython") && content.contains(datasetId)
                            && content.contains("/tmp/erp-analysis/input/")));
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
