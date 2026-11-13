package com.qiheng.erp.ai.sql.graph.node;

import com.qiheng.erp.ai.sql.graph.SqlNodeSupport;
import com.qiheng.erp.ai.sql.graph.model.SqlModelClient;
import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.qiheng.erp.ai.sql.graph.model.output.InvalidSqlModelOutput;
import com.qiheng.erp.ai.sql.graph.model.output.SqlNeedInfoOutput;
import com.qiheng.erp.ai.sql.graph.model.output.SqlQueryOutput;
import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.clearResult;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.fail;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.schema;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.update;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.work;

/** 根据需求和授权 Schema 生成 SQL。 */
public final class SqlGenerateNode implements NodeAction {
    private final SqlNodeSupport support;
    private final SqlModelClient modelClient;
    private final AiSqlProperties properties;
    private final String generatePrompt;

    /** 注入授权、模型和取数上限，提示词在初始化时加载。 */
    public SqlGenerateNode(SqlNodeSupport support, SqlModelClient modelClient, AiSqlProperties properties) {
        this.support = support;
        this.modelClient = modelClient;
        this.properties = properties;
        this.generatePrompt = SqlModelClient.loadPrompt("prompts/sql/generate.md");
    }

    /** 执行节点并返回 State 的更新。 */
    @Override
    public Map<String, Object> apply(OverAllState state) {
        Map<String, Object> work = work(state);
        if (!support.refreshAuthorization(work)) {
            clearResult(work);
            return update(work);
        }
        int attempt = ((Number) work.get("attempt")).intValue();
        if (attempt >= support.maxAttempts()) {
            return update(support.retryExhausted(work, "SQL 生成或结果检查未能通过，本次取数已停止。"));
        }
        work.put("attempt", attempt + 1);
        work.remove("code");
        work.remove("guidance");
        Map<String, Object> input = new LinkedHashMap<>();
        var snapshot = schema(work);
        input.put("requirement", work.get("requirement"));
        input.put("supplements", work.getOrDefault("supplements", List.of()));
        input.put("schema", snapshot.schemaText());
        input.put("knowledge", work.get("knowledge"));
        input.put("pageSize", properties.pageSize());
        input.put("maxTotalRows", properties.maxTotalRows());
        input.put("currentDate", work.get("currentDate"));
        input.put("timeZone", work.get("timeZone"));
        input.put("previousSql", work.getOrDefault("sql", ""));
        input.put("feedback", work.getOrDefault("feedback", ""));
        var trace = support.trace(work, "SQL_GENERATE");
        try {
            trace.record("INPUT", traceInput(input, snapshot));
            var answer = modelClient.generate(generatePrompt, input);
            trace.record("MODEL_OUTPUT", answer);
            if (answer instanceof SqlNeedInfoOutput needInfo) {
                // 尚未执行 SQL，不消耗生成和纠错预算。
                work.put("attempt", attempt);
                work.put("status", "NEED_INFO");
                work.put("message", needInfo.message());
                work.put("guidance", "可先使用其他工具获取缺失信息，或向用户询问；条件明确后重新发起查询。");
                work.put("route", "end");
                return update(work);
            }
            SqlQueryOutput query = (SqlQueryOutput) answer;
            work.put("sql", query.sql());
            work.put("needFullData", query.needFullData());
            work.put("route", "next");
            return update(work);
        } catch (JsonProcessingException | InvalidSqlModelOutput error) {
            Map<String, Object> failure = new LinkedHashMap<>();
            failure.put("stage", "generate");
            failure.put("text", String.valueOf(error.getMessage()));
            trace.record("ERROR", failure);
            return update(support.retry(work, "上次输出格式无效，请只返回符合约定的 JSON 对象：" + error.getMessage(),
                    "模型未能生成有效的查询说明。", "generate"));
        }
    }

    /**
     * 构造本轮输入的流水内容；授权 Schema 与业务口径体积大且每轮相同，改记可见范围指纹。
     *
     * @param input 本轮送入模型的输入
     * @param snapshot 本轮生效的授权快照
     * @return 可长期保存的输入内容
     */
    private Map<String, Object> traceInput(Map<String, Object> input, AuthorizedSchemaSnapshot snapshot) {
        var content = new LinkedHashMap<>(input);
        content.remove("schema");
        content.remove("knowledge");
        content.put("schemaFingerprint", snapshot.fingerprint());
        return content;
    }
}
