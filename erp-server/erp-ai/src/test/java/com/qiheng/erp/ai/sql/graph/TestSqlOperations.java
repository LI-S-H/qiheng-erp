package com.qiheng.erp.ai.sql.graph;

import com.qiheng.erp.ai.sql.graph.node.SqlPrepareNode;
import com.qiheng.erp.ai.sql.graph.node.SqlGenerateNode;
import com.qiheng.erp.ai.sql.graph.node.SqlExecuteNode;
import com.qiheng.erp.ai.sql.graph.node.SqlInspectNode;
import com.qiheng.erp.ai.sql.graph.model.SqlModelClient;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.sql.schema.SchemaProvider;
import com.qiheng.erp.ai.sql.execution.ReadOnlySqlExecutor;
import com.qiheng.erp.ai.sql.guard.SqlGuard;
import com.qiheng.erp.ai.knowledge.KnowledgeProvider;
import com.qiheng.erp.security.service.LoginUserService;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;

/** 为原有节点测试提供便捷入口，实际调用各节点的 apply。 */
final class TestSqlOperations {
    private final NodeAction prepare;
    private final NodeAction generate;
    private final NodeAction execute;
    private final NodeAction inspect;

    TestSqlOperations(SchemaProvider schemas, KnowledgeProvider knowledge, ChatModel model, ObjectMapper mapper,
                      SqlGuard guard, ReadOnlySqlExecutor executor, AiSqlProperties properties,
                      LoginUserService users, DatasetManager datasets, int maxAttempts) {
        var support = new SqlNodeSupport(schemas, knowledge, users, maxAttempts, org.mockito.Mockito.mock(com.qiheng.erp.ai.sql.graph.SqlRetryReporter.class));
        var client = new SqlModelClient(model, mapper);
        prepare = new SqlPrepareNode(support);
        generate = new SqlGenerateNode(support, client, properties);
        execute = new SqlExecuteNode(support, guard, executor, properties, datasets, mapper);
        inspect = new SqlInspectNode(support, client, guard, properties, datasets, mapper);
    }

    Map<String, Object> prepare(Map<String, Object> state) { return apply(prepare, state); }
    Map<String, Object> generate(Map<String, Object> state) { return apply(generate, state); }
    Map<String, Object> execute(Map<String, Object> state) { return apply(execute, state); }
    Map<String, Object> inspect(Map<String, Object> state) { return apply(inspect, state); }

    @SuppressWarnings("unchecked")
    private Map<String, Object> apply(NodeAction node, Map<String, Object> state) {
        try {
            return (Map<String, Object>) node.apply(new OverAllState(Map.of("sqlWork", state))).get("sqlWork");
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}
