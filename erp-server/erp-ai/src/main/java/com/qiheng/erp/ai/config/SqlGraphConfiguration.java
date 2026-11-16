package com.qiheng.erp.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import com.qiheng.erp.ai.sql.graph.SqlGraph;
import com.qiheng.erp.ai.sql.graph.SqlNodeSupport;
import com.qiheng.erp.ai.sql.graph.SqlRetryReporter;
import com.qiheng.erp.ai.sql.graph.SqlTraceSupport;
import com.qiheng.erp.ai.sql.graph.model.SqlModelClient;
import com.qiheng.erp.ai.sql.graph.node.SqlPrepareNode;
import com.qiheng.erp.ai.sql.graph.node.SqlGenerateNode;
import com.qiheng.erp.ai.sql.graph.node.SqlExecuteNode;
import com.qiheng.erp.ai.sql.graph.node.SqlInspectNode;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.dataset.tool.ListDatasetsTool;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.sql.schema.SchemaProvider;
import com.qiheng.erp.ai.sql.execution.ReadOnlySqlExecutor;
import com.qiheng.erp.ai.sql.guard.SqlGuard;
import com.qiheng.erp.ai.knowledge.KnowledgeProvider;
import com.qiheng.erp.security.service.LoginUserService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

/** 只在模型和只读取数均启用时装配 SQL 子图，不改变旧入口的停用状态。 */
@Configuration(proxyBeanMethods = false)
@Conditional({AiModelEnabledCondition.class, AiSqlEnabledCondition.class})
public class SqlGraphConfiguration {
    @Bean
    public SqlGraph sqlGraph(SchemaProvider schema,
                             KnowledgeProvider knowledge,
                             @Qualifier("flashChatModel") OpenAiChatModel model,
                             ObjectMapper mapper,
                             SqlGuard guard,
                             ReadOnlySqlExecutor executor,
                             AiSqlProperties properties,
                             LoginUserService users,
                             DatasetManager datasets,
                             SqlRetryReporter retryReporter,
                             AiAgentTraceMapper traces,
                             @Value("${erp.ai.sql.graph.max-attempts:5}") int maxAttempts) throws Exception {
        var support = new SqlNodeSupport(schema, knowledge, users, maxAttempts, retryReporter,
                new SqlTraceSupport(traces, mapper));
        var modelClient = new SqlModelClient(sqlModel(model), mapper);
        return new SqlGraph(
                new SqlPrepareNode(support),
                new SqlGenerateNode(support, modelClient, properties),
                new SqlExecuteNode(support, guard, executor, properties, datasets, mapper),
                new SqlInspectNode(support, modelClient, guard, properties, datasets, mapper));
    }

    /** 在 SQL 子图独立配置 JSON 输出，不改变共享基础模型。 */
    static ChatModel sqlModel(OpenAiChatModel model) {
        var options = ((OpenAiChatOptions) model.getDefaultOptions()).copy();
        options.setResponseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
        return model.mutate().defaultOptions(options).build();
    }

    /** 只注册工具对象，主智能体接入时再绑定到工具回调。 */
    @Bean
    public SqlQueryTool sqlQueryTool(SqlGraphService service) {
        return new SqlQueryTool(service);
    }

    /** 为主智能体提供受控的数据集分页读取。 */
    @Bean
    public ReadDatasetTool readDatasetTool(DatasetManager datasets) {
        return new ReadDatasetTool(datasets);
    }

    /** 为智能体提供当前会话已发布的数据集说明。 */
    @Bean
    public ListDatasetsTool listDatasetsTool(DatasetManager datasets) {
        return new ListDatasetsTool(datasets);
    }

    @Bean
    public SqlGraphService sqlGraphService(SqlGraph graph, AiConversationMapper conversations, DatasetManager datasets,
                                           ReadOnlySqlExecutor executor,
                                           @Value("${erp.ai.sql.graph.worker-threads:8}") int workerThreads,
                                           @Value("${erp.ai.sql.graph.queue-capacity:8}") int queueCapacity,
                                           @Value("${erp.ai.sql.graph.timeout-seconds:600}") long timeoutSeconds) {
        return new SqlGraphService(graph, conversations, datasets, executor::cancel,
                workerThreads, queueCapacity, Duration.ofSeconds(timeoutSeconds));
    }
}
