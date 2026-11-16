package com.qiheng.erp.ai.sql.graph;

import cn.hutool.core.util.IdUtil;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetReadLimitException;
import com.qiheng.erp.security.context.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** SQL 图内部入口：校验会话归属并执行独立取数。 */
public final class SqlGraphService {
    private static final Logger log = LoggerFactory.getLogger(SqlGraphService.class);
    private final SqlGraph graph;
    private final AiConversationMapper conversations;
    private final DatasetManager datasets;
    private final Consumer<String> cancelQuery;
    private final Duration timeout;
    private final ThreadPoolExecutor workers;
    private final ThreadPoolExecutor cancellations;

    public SqlGraphService(SqlGraph graph, AiConversationMapper conversations, DatasetManager datasets) {
        this(graph, conversations, datasets, runId -> {}, Duration.ofMinutes(10));
    }

    public SqlGraphService(SqlGraph graph, AiConversationMapper conversations, DatasetManager datasets,
                           Consumer<String> cancelQuery) {
        this(graph, conversations, datasets, cancelQuery, Duration.ofMinutes(10));
    }

    /** 从配置创建有界 SQL 工作队列。 */
    public SqlGraphService(SqlGraph graph, AiConversationMapper conversations, DatasetManager datasets,
                           Consumer<String> cancelQuery, int workerThreads, int queueCapacity, Duration timeout) {
        if (workerThreads < 1 || queueCapacity < 1 || timeout == null || timeout.isZero()
                || timeout.isNegative() || timeout.toMillis() < 1) {
            throw new IllegalArgumentException("SQL 工作线程数、等待队列容量和超时时间必须大于零");
        }
        this.graph = graph;
        this.conversations = conversations;
        this.datasets = datasets;
        this.cancelQuery = cancelQuery;
        this.timeout = timeout;
        this.workers = new ThreadPoolExecutor(workerThreads, workerThreads, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                Thread.ofPlatform().daemon().name("ai-sql-", 0).factory());
        this.cancellations = new ThreadPoolExecutor(0, 8, 60, TimeUnit.SECONDS, new SynchronousQueue<>(),
                Thread.ofPlatform().daemon().name("ai-sql-cancel-", 0).factory());
    }

    /** 每次查询独立运行；限制工作线程数，防止无法中断的调用无限累积。 */
    SqlGraphService(SqlGraph graph, AiConversationMapper conversations, DatasetManager datasets,
                    Consumer<String> cancelQuery, Duration timeout) {
        this(graph, conversations, datasets, cancelQuery, 8, 8, timeout);
    }

    /** 使用当前用户身份执行独立取数。 */
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
        FutureTask<Map<String, Object>> task = new FutureTask<>(() -> {
            Map<String, Object> finished = graph.start(input);
            // 在执行线程真正结束后清理，不由超时的调用线程提前清理。
            try {
                datasets.cleanupStaged(sqlRunId, userId, conversationId);
            } catch (RuntimeException error) {
                log.warn("SQL 取数暂存数据清理失败 runId={}", sqlRunId, error);
            }
            return result(finished);
        });
        try {
            workers.execute(task);
        } catch (RejectedExecutionException error) {
            return Map.of("status", "FAILED", "code", "SQL_BUSY", "message", "当前查询任务较多，请稍后重试。");
        }
        try {
            return task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            task.cancel(true);
            workers.remove(task);
            requestCancellation(sqlRunId);
            return Map.of("status", "FAILED", "code", "SQL_TIMEOUT", "message", "本次查询超时，未取得结果。",
                    "guidance", "可考虑缩小查询范围或稍后重试，不要将本次查询当作成功。");
        } catch (InterruptedException error) {
            task.cancel(true);
            workers.remove(task);
            requestCancellation(sqlRunId);
            Thread.currentThread().interrupt();
            throw error;
        } catch (CancellationException error) {
            return Map.of("status", "FAILED", "code", "SQL_CANCELLED", "message", "本次查询已取消。");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw new IllegalStateException("SQL 子图执行失败", error.getCause());
        }
    }

    /** JDBC 取消可能阻塞，因此不让它拖住已超时的调用方。 */
    private void requestCancellation(String sqlRunId) {
        try {
            cancellations.execute(() -> {
                try {
                    cancelQuery.accept(sqlRunId);
                } catch (RuntimeException error) {
                    log.warn("请求取消 SQL 查询失败 runId={}", sqlRunId, error);
                }
            });
        } catch (RejectedExecutionException error) {
            log.warn("取消 SQL 查询的线程已满 runId={}", sqlRunId, error);
        }
    }

    /** 应用停止时请求关闭执行线程，不等待无法中断的数据库调用。 */
    @PreDestroy
    public void shutdown() {
        // shutdownNow 只移出排队任务；显式取消才能立即唤醒等待结果的调用方。
        for (Runnable pending : workers.shutdownNow()) {
            if (pending instanceof FutureTask<?> task) {
                task.cancel(false);
            }
        }
        cancellations.shutdownNow();
    }

    private void ownedUser(long userId, long conversationId) {
        if (conversations.selectOwned(conversationId, userId) == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
    }

    /** 生成本次取数的独立标识，关联暂存和正式数据集。 */
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
        validated.put("dataRequirement", choice.toUpperCase(Locale.ROOT));
        return validated;
    }

    /** 上层只接收交付信息，不接收授权 Schema、候选 SQL 和临时反馈。 */
    private Map<String, Object> result(Map<String, Object> work) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : List.of("sqlRunId", "status", "code", "message", "guidance", "datasetId", "columns", "rowCount",
                "completed", "truncated", "truncationReason", "businessFullResult", "limited", "summary", "limitations", "dataDescription")) {
            if (work.containsKey(key)) {
                result.put(key, work.get(key));
            }
        }
        if ("SUCCESS".equals(work.get("status")) || "PARTIAL".equals(work.get("status"))) {
            // 预览与正式数据共用权限和大小限制；主智能体无需额外调用即可理解小结果。
            try {
                var preview = datasets.readPage((String) work.get("datasetId"),
                    SqlNodeSupport.id(work, "userId"), SqlNodeSupport.id(work, "conversationId"), 0, 5);
                result.put("preview", Map.of("rows", preview.rows(), "returnedRows", preview.returnedRows(),
                        "datasetRows", preview.rowCount(), "containsAllRows", !preview.hasMore() && !preview.datasetTruncated(),
                        "message", preview.datasetTruncated() ? preview.completenessMessage() : preview.hasMore() ? "仅为部分预览，不能代表全部数据。" : "预览包含数据集全部行。"));
            } catch (DatasetReadLimitException error) {
                // 可选预览超限不影响已发布数据交付；权限和存储错误仍由调用方处理。
                result.put("preview", Map.of("rows", List.of(), "containsAllRows", false, "message", error.getMessage()));
            }
        }
        return result;
    }

}
