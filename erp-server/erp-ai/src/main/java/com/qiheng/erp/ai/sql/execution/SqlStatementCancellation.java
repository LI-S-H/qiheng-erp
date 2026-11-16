package com.qiheng.erp.ai.sql.execution;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ConcurrentHashMap;

/** 按查询标识登记正在执行的语句，避免取消其他任务。 */
final class SqlStatementCancellation {
    private final ConcurrentHashMap<String, ActiveQuery> queries = new ConcurrentHashMap<>();

    ActiveQuery begin(String runId) {
        ActiveQuery query = new ActiveQuery();
        if (queries.putIfAbsent(runId, query) != null) {
            throw new IllegalStateException("SQL 执行标识重复");
        }
        return query;
    }

    void finish(String runId, ActiveQuery query) {
        queries.remove(runId, query);
    }

    void cancel(String runId) throws SQLException {
        ActiveQuery query = queries.get(runId);
        if (query != null) {
            query.cancel();
        }
    }

    /** 标记取消与登记语句同步，防止翻页间隙漏掉取消请求。 */
    static final class ActiveQuery {
        private Statement statement;
        private boolean cancelled;

        synchronized void register(Statement current) throws SQLException {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new SQLException("SQL 任务已取消");
            }
            statement = current;
        }

        synchronized void clear(Statement current) {
            if (statement == current) {
                statement = null;
            }
        }

        void cancel() throws SQLException {
            Statement current;
            synchronized (this) {
                cancelled = true;
                current = statement;
            }
            // JDBC 驱动可能阻塞，不能持锁等待，否则查询线程无法清理当前语句。
            if (current != null) {
                current.cancel();
            }
        }
    }
}
