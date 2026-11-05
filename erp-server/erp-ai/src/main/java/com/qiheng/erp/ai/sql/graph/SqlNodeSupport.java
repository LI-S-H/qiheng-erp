package com.qiheng.erp.ai.sql.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;
import com.qiheng.erp.ai.sql.schema.SchemaProvider;
import com.qiheng.erp.ai.sql.scope.PermissionCodes;
import com.qiheng.erp.ai.knowledge.KnowledgeProvider;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SQL 节点共用的权限刷新、状态读取和重试规则。 */
public final class SqlNodeSupport {
    public static final String WORK = "sqlWork";
    private final SchemaProvider schemas;
    private final KnowledgeProvider knowledge;
    private final LoginUserService users;
    private final int maxAttempts;
    private final SqlRetryReporter retryReporter;

    /** 准备授权依赖与生成、纠错共用的次数上限。 */
    public SqlNodeSupport(SchemaProvider schemas, KnowledgeProvider knowledge, LoginUserService users, int maxAttempts, SqlRetryReporter retryReporter) {
        if (maxAttempts < 1) throw new IllegalArgumentException("SQL 最大尝试次数必须大于零");
        this.schemas = schemas;
        this.knowledge = knowledge;
        this.users = users;
        this.maxAttempts = maxAttempts;
        this.retryReporter = retryReporter;
    }

    /** 每次恢复或查询时读取当前授权，避免使用已撤销的权限。 */
    public boolean refreshAuthorization(Map<String, Object> work) {
        LoginUser user = users.findByUserId(id(work, "userId"));
        List<String> permissions = PermissionCodes.of(user);
        if (user == null || (!permissions.contains("*") && !permissions.contains("ai:assistant:use"))) {
            fail(work, "AI_PERMISSION_DENIED", "当前用户已不可用或无 AI 使用权限，无法执行本次取数。", "请说明权限限制，不要重复调用 SQL 工具。");
            return false;
        }
        AuthorizedSchemaSnapshot snapshot = schemas.provideFreshSnapshot(permissions);
        if (snapshot.tables().isEmpty()) {
            fail(work, "DATA_ACCESS_DENIED", "当前用户没有可查询的业务数据权限，无法执行本次取数。", "请说明权限限制，或使用其他已授权的数据来源。");
            return false;
        }
        work.put("schema", snapshot.toMap());
        work.put("knowledge", knowledge.provide(permissions));
        return true;
    }

    /** 取得 State 的工作副本，节点通过返回值更新框架状态。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> work(OverAllState state) {
        return new LinkedHashMap<>((Map<String, Object>) state.value(WORK).orElseThrow());
    }

    /** 将工作状态作为整体替换，避免上轮查询字段残留。 */
    public static Map<String, Object> update(Map<String, Object> work) {
        return Map.of(WORK, work);
    }

    /** 消耗统一尝试预算，预算耗尽时终止取数。 */
    public Map<String, Object> retry(Map<String, Object> work, String feedback, String message, String stage) {
        clearResult(work);
        work.remove("code");
        work.remove("guidance");
        work.put("feedback", feedback);
        work.put("retryStage", stage);
        if (((Number) work.get("attempt")).intValue() >= maxAttempts) {
            return retryExhausted(work, message + " 多次尝试仍未通过，本次取数已停止。");
        }
        work.put("route", "retry");
        return work;
    }

    /** 两条耗尽路径共用上报和结束处理，恢复重放使用同一事件标识。 */
    public Map<String, Object> retryExhausted(Map<String, Object> work, String message) {
        retryReporter.report(String.valueOf(work.get("sqlRunId")),
                String.valueOf(work.getOrDefault("retryStage", "generate")),
                ((Number) work.get("attempt")).intValue(),
                "inspect".equals(work.get("retryStage")) ? (String) work.get("retryDiagnostic") : null);
        return fail(work, "SQL_RETRY_EXHAUSTED", message,
                "不要原样重复调用；可考虑其他已授权的数据来源，或向用户说明无法取数。");
    }

    /** 返回一次取数允许的最大生成次数。 */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** 撤销结果投影，不立即删除仍可能被检查点引用的暂存数据。 */
    public static void clearResult(Map<String, Object> work) {
        for (String key : List.of("datasetId", "columns", "rowCount", "truncated", "completed",
                "limited", "limit", "limitations", "truncationReason", "businessFullResult", "summary", "dataDescription")) work.remove(key);
    }

    /** 标记本次取数结束及其原因。 */
    public static Map<String, Object> finish(Map<String, Object> work, String status, String message) {
        if (!"FAILED".equals(status)) {
            work.remove("code");
            work.remove("guidance");
        }
        work.put("status", status);
        work.put("message", message);
        work.put("route", "end");
        return work;
    }

    /** 将明确失败原因和后续建议交给上层。 */
    public static Map<String, Object> fail(Map<String, Object> work, String code, String message, String guidance) {
        work.put("code", code);
        work.put("guidance", guidance);
        return finish(work, "FAILED", message);
    }

    /** 将检查点中的 JSON 结构还原为授权快照。 */
    @SuppressWarnings("unchecked")
    public static AuthorizedSchemaSnapshot schema(Map<String, Object> work) {
        return AuthorizedSchemaSnapshot.fromMap((Map<String, Object>) work.get("schema"));
    }

    /** 获取上层提交的取数需求。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> requirement(Map<String, Object> work) {
        return (Map<String, Object>) work.get("requirement");
    }

    /** 业务完整性不等同于执行器的游标全量标记。 */
    public static boolean requiresFullResult(Map<String, Object> work) {
        return "FULL".equalsIgnoreCase(String.valueOf(requirement(work).get("dataRequirement")));
    }

    /** 兼容检查点恢复后的数字表示。 */
    public static long id(Map<String, Object> work, String key) {
        return Long.parseLong(String.valueOf(work.get(key)));
    }
}
