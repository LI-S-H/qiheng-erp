package com.qiheng.erp.ai.sql.graph.node;

import com.qiheng.erp.ai.sql.graph.SqlNodeSupport;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLLimit;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetRecord;
import com.qiheng.erp.ai.dataset.DatasetRequest;
import com.qiheng.erp.ai.sql.execution.ReadOnlySqlExecutor;
import com.qiheng.erp.ai.sql.execution.model.ExecutionOutcome;
import com.qiheng.erp.ai.sql.execution.model.SqlErrorKind;
import com.qiheng.erp.ai.sql.execution.model.SqlExecutionRequest;
import com.qiheng.erp.ai.sql.guard.SqlGuard;
import com.qiheng.erp.ai.sql.guard.model.GuardResult;
import com.qiheng.erp.ai.sql.guard.model.SqlGuardLimits;
import com.qiheng.erp.ai.sql.guard.model.SqlGuardRejectCode;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.finish;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.fail;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.id;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.requirement;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.requiresFullResult;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.schema;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.update;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.work;

/** 校验 SQL、只读执行并暂存查询结果。 */
public final class SqlExecuteNode implements NodeAction {
    private final SqlNodeSupport support;
    private final SqlGuard guard;
    private final ReadOnlySqlExecutor executor;
    private final AiSqlProperties properties;
    private final DatasetManager datasets;
    private final ObjectMapper mapper;

    /** 注入权限、SQL 校验与执行，以及数据集暂存能力。 */
    public SqlExecuteNode(SqlNodeSupport support, SqlGuard guard, ReadOnlySqlExecutor executor,
                          AiSqlProperties properties, DatasetManager datasets, ObjectMapper mapper) {
        this.support = support;
        this.guard = guard;
        this.executor = executor;
        this.properties = properties;
        this.datasets = datasets;
        this.mapper = mapper;
    }

    /** 执行节点并返回 State 的更新。 */
    @Override
    public Map<String, Object> apply(OverAllState state) {
        Map<String, Object> work = work(state);
        if (!support.refreshAuthorization(work)) {
            return update(work);
        }
        String sql = (String) work.get("sql");
        boolean needFullData = requiresFullResult(work);
        GuardResult checked = guard.check(sql, schema(work), new SqlGuardLimits(properties.maxTotalRows()), needFullData);
        if (checked instanceof GuardResult.Rejected(SqlGuardRejectCode code, String message)) {
            if (code == SqlGuardRejectCode.SYSTEM_SCHEMA) {
                return update(fail(work, "SQL_SCOPE_REJECTED", "查询涉及不允许访问的数据库范围，本次取数已停止。",
                        "不要访问系统库或其他数据库；请说明当前查询受限。"));
            }
            return update(support.retry(work, message, "查询未通过当前授权或取数规则检查。", "guard"));
        }
        var guarded = ((GuardResult.Ok) checked).guardedSql();
        boolean limited = hasLimit(sql);
        if (requiresFullResult(work) && limited) {
            return update(support.retry(work, "本次要求完整结果，不得用 LIMIT 截取部分数据冒充完整结果。", "尚未取得符合要求的完整数据。", "guard"));
        }
        work.put("limited", limited);
        if (guarded.limit() != null) {
            work.put("limit", guarded.limit());
        }
        work.put("limitations", !limited ? "" : guarded.limit() == null
                ? "本次查询包含内层 LIMIT，结果受限，不代表全部记录。"
                : "本次为限量结果，最多 " + guarded.limit() + " 行，不代表全部记录。");

        String sqlRunId = (String) work.get("sqlRunId");
        int attempt = ((Number) work.get("attempt")).intValue();
        ExecutionOutcome outcome = executor.execute(new SqlExecutionRequest(sqlRunId,
                sqlRunId + ":" + attempt, guarded, needFullData));
        if (outcome instanceof ExecutionOutcome.LimitExceeded(int maxRows)) {
            return update(fail(work, "SQL_RESULT_LIMIT_EXCEEDED",
                    "查询结果超过系统单次取数上限 " + maxRows + " 行，无法交付完整结果。",
                    "请说明完整数据暂不可用；可按业务范围拆分查询或使用其他已授权的数据来源，不要将截取结果当作完整结果。"));
        }
        if (outcome instanceof ExecutionOutcome.IncompleteResult) {
            return update(fail(work, "SQL_RESULT_LIMIT_EXCEEDED",
                    "查询触及分页或行数上限，无法交付完整结果。",
                    "请说明完整数据暂不可用；可按业务范围拆分查询或使用其他已授权的数据来源，不要将截取结果当作完整结果。"));
        }
        if (outcome instanceof ExecutionOutcome.Failed(SqlErrorKind kind, String modelMessage, String userMessage)) {
            // 连接、认证、权限等问题不靠模型改写 SQL 解决，避免反复执行无意义的尝试。
            if (kind == SqlErrorKind.SYNTAX) {
                return update(support.retry(work, modelMessage, userMessage, "execute"));
            }
            if (kind == SqlErrorKind.PERMISSION) {
                return update(fail(work, "DATA_ACCESS_DENIED", userMessage + "，无法完成本次取数。",
                        "请说明数据库访问权限限制，不要重复执行同一查询。"));
            }
            return update(fail(work, "SQL_EXECUTION_UNAVAILABLE", userMessage + "，本次未取得数据。",
                    "请说明查询未完成，可考虑其他已授权的数据来源或稍后重试。"));
        }
        var result = ((ExecutionOutcome.Success) outcome).result();
        DatasetRecord staged = datasets.stage(new DatasetRequest(sqlRunId,
                id(work, "userId"), id(work, "conversationId"),
                String.valueOf(requirement(work).get("goal")), sql, attempt), result);
        work.put("datasetId", staged.datasetId());
        work.put("columns", mapper.convertValue(staged.columns(), List.class));
        work.put("rowCount", staged.rowCount());
        work.put("completed", staged.completed());
        work.put("route", "next");
        return update(work);
    }

    /** 检查 SQL 内外层是否存在 LIMIT，避免误认为取得全量。 */
    private boolean hasLimit(String sql) {
        AtomicBoolean found = new AtomicBoolean();
        SQLUtils.parseSingleMysqlStatement(sql).accept(new MySqlASTVisitorAdapter() {
            /** 标记任意层级的分页，避免只检查最外层 LIMIT。 */
            @Override
            public boolean visit(SQLLimit limit) {
                found.set(true);
                return false;
            }
        });
        return found.get();
    }
}
