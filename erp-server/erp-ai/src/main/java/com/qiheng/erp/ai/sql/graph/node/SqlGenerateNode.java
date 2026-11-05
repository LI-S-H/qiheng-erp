package com.qiheng.erp.ai.sql.graph.node;

import com.qiheng.erp.ai.sql.graph.SqlNodeSupport;
import com.qiheng.erp.ai.sql.graph.model.SqlModelClient;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.qiheng.erp.ai.sql.graph.model.output.InvalidSqlModelOutput;
import com.qiheng.erp.ai.sql.graph.model.output.SqlNeedInfoOutput;
import com.qiheng.erp.ai.sql.graph.model.output.SqlQueryOutput;
import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import java.util.LinkedHashMap;
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
        input.put("requirement", work.get("requirement"));
        input.put("supplements", work.getOrDefault("supplements", java.util.List.of()));
        input.put("schema", schema(work).schemaText());
        input.put("knowledge", work.get("knowledge"));
        input.put("pageSize", properties.pageSize());
        input.put("maxTotalRows", properties.maxTotalRows());
        input.put("currentDate", work.get("currentDate"));
        input.put("timeZone", work.get("timeZone"));
        input.put("previousSql", work.getOrDefault("sql", ""));
        input.put("feedback", work.getOrDefault("feedback", ""));
        try {
            var answer = modelClient.generate(generatePrompt, input);
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
            return update(support.retry(work, "上次输出格式无效，请只返回符合约定的 JSON 对象：" + error.getMessage(),
                    "模型未能生成有效的查询说明。", "generate"));
        }
    }
}
