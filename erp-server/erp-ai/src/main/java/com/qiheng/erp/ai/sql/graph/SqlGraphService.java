package com.qiheng.erp.ai.sql.graph;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import cn.hutool.core.util.IdUtil;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.sql.scope.PermissionCodes;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.service.LoginUserService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** SQL 图内部入口：按用户和会话隔离，并由调用方确认结果交接。 */
public final class SqlGraphService {
    private static final Logger log = LoggerFactory.getLogger(SqlGraphService.class);
    private final SqlGraph graph;
    private final BaseCheckpointSaver saver;
    private final AiConversationMapper conversations;
    private final RedissonClient redis;
    private final LoginUserService users;
    private final DatasetManager datasets;

    public SqlGraphService(SqlGraph graph, BaseCheckpointSaver saver, AiConversationMapper conversations,
                           RedissonClient redis, LoginUserService users, DatasetManager datasets) {
        this.graph = graph;
        this.saver = saver;
        this.conversations = conversations;
        this.redis = redis;
        this.users = users;
        this.datasets = datasets;
    }

    /** 同一会话串行执行，每次新任务用完整输入替换上一轮工作状态。 */
    public Map<String, Object> startForCurrentUser(long conversationId, String sqlRunId,
                                     Map<String, Object> requirement) throws Exception {
        return startForUser(UserContext.requireCurrentUser().getUserId(), conversationId, sqlRunId, requirement);
    }

    /** 使用服务端确认的用户身份启动取数，并校验会话归属。 */
    public Map<String, Object> startForUser(long userId, long conversationId, String sqlRunId,
                                     Map<String, Object> requirement) throws Exception {
        return startForUserAtDate(userId, conversationId, sqlRunId, requirement, null);
    }

    /** 使用可信运行上下文的日期；独立调用时由准备节点确定日期。 */
    public Map<String, Object> startForUserAtDate(long userId, long conversationId, String sqlRunId,
                                     Map<String, Object> requirement, String businessDate) throws Exception {
        ownedUser(userId, conversationId);
        RLock lock = lock(conversationId);
        try {
            String threadId = threadId(userId, conversationId);
            Map<String, Object> previous = current(threadId);
            if (!previous.isEmpty()) {
                var permissions = PermissionCodes.of(users.findByUserId(userId));
                if (!permissions.contains("*") && !permissions.contains("ai:assistant:use")) {
                    var denied = permissionDenied((String) previous.get("sqlRunId"));
                    denied.put("existing", true);
                    return denied;
                }
                var checkpoint = saver.get(RunnableConfig.builder().threadId(threadId).build()).orElseThrow();
                if ("__END__".equals(checkpoint.getNextNodeId())
                        && ("SUCCESS".equals(previous.get("status")) || "PARTIAL".equals(previous.get("status")))) {
                    datasets.get((String) previous.get("datasetId"), userId, conversationId);
                }
                Map<String, Object> pending = result(previous);
                pending.put("existing", true);
                pending.put("recoveryRequired", !"__END__".equals(checkpoint.getNextNodeId()));
                return pending;
            }
            Map<String, Object> input = new HashMap<>();
            input.put("userId", userId);
            input.put("conversationId", conversationId);
            input.put("sqlRunId", sqlRunId);
            input.put("requirement", validatedRequirement(requirement));
            if (businessDate != null) {
                input.put("currentDate", businessDate);
            }
            input.put("attempt", 0);
            input.put("supplements", List.of());
            input.put("route", "next");
            Map<String, Object> finished = graph.start(threadId, input);
            cleanupIfComplete(threadId, sqlRunId, userId, conversationId);
            return result(finished);
        } finally {
            lock.unlock();
        }
    }

    /** 仅允许会话所有者恢复当前取数，执行节点会重新检查最新权限。 */
    public Map<String, Object> recoverForCurrentUser(long conversationId, String sqlRunId) throws Exception {
        return recoverForUser(UserContext.requireCurrentUser().getUserId(), conversationId, sqlRunId);
    }

    /** 使用可信用户身份恢复任务。 */
    public Map<String, Object> recoverForUser(long userId, long conversationId, String sqlRunId) throws Exception {
        ownedUser(userId, conversationId);
        RLock lock = lock(conversationId);
        try {
            String threadId = threadId(userId, conversationId);
            var config = RunnableConfig.builder().threadId(threadId).build();
            var checkpoint = saver.get(config).orElseThrow(() -> new IllegalStateException("SQL 取数检查点不存在"));
            Map<String, Object> work = work(checkpoint.getState());
            if (!sqlRunId.equals(work.get("sqlRunId"))) {
                throw new IllegalStateException("SQL 取数标识与当前检查点不一致");
            }
            var permissions = PermissionCodes.of(users.findByUserId(userId));
            if (!permissions.contains("*") && !permissions.contains("ai:assistant:use")) {
                return permissionDenied(sqlRunId);
            }
            if ("__END__".equals(checkpoint.getNextNodeId())) {
                if ("SUCCESS".equals(work.get("status")) || "PARTIAL".equals(work.get("status"))) {
                    datasets.get((String) work.get("datasetId"), userId, conversationId);
                }
                cleanupIfComplete(threadId, sqlRunId, userId, conversationId);
                return result(work);
            }
            Map<String, Object> finished = graph.resume(threadId, checkpoint.getId());
            cleanupIfComplete(threadId, sqlRunId, userId, conversationId);
            return result(finished);
        } finally {
            lock.unlock();
        }
    }

    /** 上层已可靠保存结果后调用；清空当前状态但不删除框架历史检查点。 */
    public void acknowledgeForCurrentUser(long conversationId, String sqlRunId) throws Exception {
        acknowledgeForUser(UserContext.requireCurrentUser().getUserId(), conversationId, sqlRunId);
    }

    /** 上层可靠保存结果后，以可信用户身份确认交接。 */
    public void acknowledgeForUser(long userId, long conversationId, String sqlRunId) throws Exception {
        ownedUser(userId, conversationId);
        RLock lock = lock(conversationId);
        try {
            String threadId = threadId(userId, conversationId);
            var config = RunnableConfig.builder().threadId(threadId).build();
            var checkpoint = saver.get(config).orElseThrow(() -> new IllegalStateException("SQL 取数检查点不存在"));
            Map<String, Object> work = work(checkpoint.getState());
            if (!sqlRunId.equals(work.get("sqlRunId")) || !"__END__".equals(checkpoint.getNextNodeId())) {
                throw new IllegalStateException("只能确认当前已结束的 SQL 取数");
            }
            // 清理失败保留当前检查点，确保下一次确认仍有机会重试。
            datasets.cleanupStaged(sqlRunId, userId, conversationId);
            graph.clear(threadId);
        } finally {
            lock.unlock();
        }
    }

    private void ownedUser(long userId, long conversationId) {
        if (conversations.selectOwned(conversationId, userId) == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
    }

    /** 返回权限拒绝而不改写检查点，权限恢复后仍可处理原任务。 */
    private Map<String, Object> permissionDenied(String sqlRunId) {
        Map<String, Object> denied = new LinkedHashMap<>();
        denied.put("sqlRunId", sqlRunId);
        denied.put("status", "FAILED");
        denied.put("code", "AI_PERMISSION_DENIED");
        denied.put("message", "当前用户没有 AI 使用权限，无法继续本次取数。");
        denied.put("guidance", "请说明权限限制，不要重复调用 SQL 工具。");
        return denied;
    }

    /** 上层先生成并记录执行 ID，以便调用中断后仍能恢复同一次取数。 */
    public static String newRunId() {
        return "sqlrun_" + IdUtil.simpleUUID();
    }

    /** 完整数据要求必须显式传入，避免把未知值误判为允许截断。 */
    private static Map<String, Object> validatedRequirement(Map<String, Object> requirement) {
        Object goal = requirement.get("goal");
        Object mode = requirement.get("dataRequirement");
        if (!(goal instanceof String text) || text.isBlank()
                || !(mode instanceof String choice)
                || !("FULL".equalsIgnoreCase(choice) || "LIMITED".equalsIgnoreCase(choice))) {
            throw new IllegalArgumentException("取数需求必须包含 goal 和 dataRequirement（FULL 或 LIMITED）");
        }
        Map<String, Object> validated = new LinkedHashMap<>(requirement);
        validated.put("dataRequirement", choice.toUpperCase(java.util.Locale.ROOT));
        return validated;
    }

    /** 上层只接收交付信息，不接收授权 Schema、候选 SQL 和临时反馈。 */
    private Map<String, Object> result(Map<String, Object> work) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("sqlRunId", "status", "code", "message", "guidance", "datasetId", "columns", "rowCount",
                "completed", "truncated", "truncationReason", "businessFullResult", "limited", "summary", "limitations", "dataDescription")) {
            if (work.containsKey(key)) result.put(key, work.get(key));
        }
        if ("SUCCESS".equals(work.get("status")) || "PARTIAL".equals(work.get("status"))) {
            // 预览与正式数据共用权限和大小限制；主智能体无需额外调用即可理解小结果。
            try {
                var preview = datasets.readPage((String) work.get("datasetId"),
                    SqlNodeSupport.id(work, "userId"), SqlNodeSupport.id(work, "conversationId"), 0, 5);
                result.put("preview", Map.of("rows", preview.rows(), "returnedRows", preview.returnedRows(),
                        "datasetRows", preview.rowCount(), "containsAllRows", !preview.hasMore() && !preview.datasetTruncated(),
                        "message", preview.datasetTruncated() ? preview.completenessMessage() : preview.hasMore() ? "仅为部分预览，不能代表全部数据。" : "预览包含数据集全部行。"));
            } catch (com.qiheng.erp.ai.dataset.DatasetReadLimitException error) {
                // 可选预览超限不影响已发布数据交付；权限和存储错误仍由调用方处理。
                result.put("preview", Map.of("rows", List.of(), "containsAllRows", false, "message", error.getMessage()));
            }
        }
        return result;
    }

    private RLock lock(long conversationId) {
        // 子图锁独立于主智能体执行锁，避免工具运行在线程池时自锁。
        RLock lock = redis.getLock("erp:ai:sql:execution:" + conversationId);
        if (!lock.tryLock()) {
            throw new IllegalStateException("会话正在执行其他任务");
        }
        return lock;
    }

    private Map<String, Object> current(String threadId) {
        var config = RunnableConfig.builder().threadId(threadId).build();
        return saver.get(config).map(checkpoint -> work(checkpoint.getState())).orElse(Map.of());
    }

    /** 只在最终检查点已保存后清理，失败不覆盖已完成的取数结果。 */
    private void cleanupIfComplete(String threadId, String sqlRunId, long userId, long conversationId) {
        try {
            var config = RunnableConfig.builder().threadId(threadId).build();
            var checkpoint = saver.get(config);
            if (checkpoint.isEmpty() || !"__END__".equals(checkpoint.get().getNextNodeId())
                    || !sqlRunId.equals(work(checkpoint.get().getState()).get("sqlRunId"))) return;
            datasets.cleanupStaged(sqlRunId, userId, conversationId);
        } catch (RuntimeException error) {
            log.warn("SQL 取数暂存数据清理失败，可在结果交接时重试 runId={}", sqlRunId, error);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> work(Map<String, Object> state) {
        return (Map<String, Object>) state.getOrDefault(SqlNodeSupport.WORK, Map.of());
    }

    private static String threadId(long userId, long conversationId) {
        return "sql:" + userId + ":" + conversationId;
    }
}
