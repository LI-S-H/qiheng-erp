package com.qiheng.erp.ai.service.data.sql.execution;

import com.qiheng.erp.ai.config.AiSqlEnabledCondition;
import com.qiheng.erp.ai.service.data.sql.guard.GuardedSql;
import com.qiheng.erp.ai.service.data.sql.pool.ReadOnlySqlPool;
import com.qiheng.erp.ai.service.data.sql.pool.ReadOnlySqlPoolSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 取数执行器：把守卫放行的 SQL 安全地跑完、把结果完整带回来。
 *
 * <p>连接池由 {@link ReadOnlySqlPool} 私有持有；本类只负责三条取数路径与错误分类。</p>
 *
 * <p><b>三条路径</b>：SQL 里有 LIMIT → 按 LIMIT 取；声明要全量 → 游标翻页；
 * 没写 LIMIT 又没声明全量 → 先探测，超页就让模型补声明。三条都不静默截断。</p>
 *
 * @author Li
 * @since 2026-10-25
 */
@Slf4j
@Component
@Conditional(AiSqlEnabledCondition.class)
public class ReadOnlySqlExecutor implements DisposableBean {

    /** 错误信息进日志/提示时的最大长度，避免把整段 SQL 或堆栈塞给模型。 */
    private static final int MAX_ERROR_DETAIL = 300;

    private final ReadOnlySqlPool pool;

    private final SqlExecutionLimits limits;

    private final CursorPager pager;

    public ReadOnlySqlExecutor(
            @Value("${erp.ai.sql.datasource.url:}") String url,
            @Value("${erp.ai.sql.datasource.username:}") String username,
            @Value("${erp.ai.sql.datasource.password:}") String password,
            @Value("${erp.ai.sql.datasource.driver-class-name:com.mysql.cj.jdbc.Driver}") String driverClassName,
            @Value("${erp.ai.sql.datasource.pool-name:erp-ai-readonly}") String poolName,
            @Value("${erp.ai.sql.datasource.initial-size:1}") int initialSize,
            @Value("${erp.ai.sql.datasource.min-idle:1}") int minIdle,
            @Value("${erp.ai.sql.datasource.max-active:2}") int maxActive,
            @Value("${erp.ai.sql.datasource.max-wait-ms:3000}") long maxWaitMs,
            @Value("${erp.ai.sql.datasource.validation-query:SELECT 1}") String validationSql,
            @Value("${erp.ai.sql.datasource.slow-sql-millis:1000}") long slowSqlMillis,
            @Value("${erp.ai.sql.session-read-only:true}") boolean sessionReadOnly,
            @Value("${erp.ai.sql.page-size:200}") int pageSize,
            @Value("${erp.ai.sql.max-total-rows:5000}") int maxTotalRows,
            @Value("${erp.ai.sql.max-pages:10}") int maxPages,
            @Value("${erp.ai.sql.query-timeout-seconds:10}") int timeoutSeconds) {
        this(new ReadOnlySqlPool(new ReadOnlySqlPoolSettings(url, username, password, driverClassName,
                        poolName, initialSize, minIdle, maxActive, maxWaitMs, validationSql,
                        slowSqlMillis, sessionReadOnly)),
                new SqlExecutionLimits(pageSize, maxTotalRows, maxPages, timeoutSeconds));
        log.info("AI 取数连接池已就绪 poolName={} maxActive={} 只读账号={}", poolName, maxActive, username);
    }

    /**
     * 测试用：直接给池与上限。
     *
     * @param pool   只读池
     * @param limits 取数上限
     */
    ReadOnlySqlExecutor(ReadOnlySqlPool pool, SqlExecutionLimits limits) {
        this.pool = pool;
        this.limits = limits;
        this.pager = new CursorPager(limits);
    }

    /**
     * 执行一条守卫放行的 SQL。
     *
     * @param request 请求
     * @return 成功、需要模型补声明、或失败
     */
    public ExecutionOutcome execute(SqlExecutionRequest request) {
        GuardedSql guardedSql = request.guardedSql();
        long started = System.nanoTime();
        String sqlHash = hash(guardedSql.sql());
        try (Connection connection = openConnection()) {
            if (guardedSql.limit() != null) {
                return executeWithLimit(connection, guardedSql, started, sqlHash);
            }
            if (request.needFullData()) {
                return executePaged(connection, guardedSql, request, started, sqlHash);
            }
            return executeProbe(connection, guardedSql, started, sqlHash);
        } catch (SQLException error) {
            return failure(error, guardedSql, request);
        } catch (RuntimeException error) {
            // 守卫已经解析过同一条 SQL，走到这里说明改写或读取出了意料之外的问题
            log.error("取数执行异常 runId={} stepId={}", request.runId(), request.stepId(), error);
            return new ExecutionOutcome.Failed(SqlErrorKind.UNKNOWN,
                    "查询执行失败：" + safeDetail(error.getMessage()),
                    SqlErrorKind.UNKNOWN.userMessage());
        }
    }

    /**
     * 连通性与身份探测：确认连的是哪个库、以哪个用户身份连上的。
     *
     * @return 连接身份信息
     * @throws SQLException 连接或查询失败
     */
    public ConnectionInfo probe() throws SQLException {
        try (Connection connection = openConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(
                     "SELECT CURRENT_USER(), DATABASE(), VERSION()")) {
            resultSet.next();
            return new ConnectionInfo(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3));
        }
    }

    /**
     * 打开一条只读连接。
     *
     * <p>基础层内部与自检用；节点层不直接拿连接，查询一律走 {@link #execute}。</p>
     *
     * @return 连接，调用方负责关闭
     * @throws SQLException 取连接失败
     */
    Connection openConnection() throws SQLException {
        return pool.open();
    }

    @Override
    public void destroy() {
        pool.close();
        log.info("AI 取数连接池已关闭");
    }

    // ==================== 三条路径 ====================

    /**
     * 路径 A：模型自己写了 LIMIT。按它要的条数取，多取一行只为知道"还有没有"。
     */
    private ExecutionOutcome executeWithLimit(Connection connection, GuardedSql guardedSql,
                                              long started, String sqlHash) throws SQLException {
        int limit = guardedSql.limit();
        try (PreparedStatement statement = connection.prepareStatement(guardedSql.sql())) {
            statement.setQueryTimeout(limits.timeoutSeconds());
            statement.setMaxRows(limit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                QueryResult result = readAll(resultSet, limit + 1, started, sqlHash);
                if (result.rowCount() > limit) {
                    result = new QueryResult(result.columns(), result.rows().subList(0, limit), limit,
                            false, true, 1, result.elapsedMillis(), result.sqlHash());
                }
                log.info("取数完成（LIMIT） hash={} rows={} elapsedMs={}",
                        sqlHash.substring(0, 8), result.rowCount(), result.elapsedMillis());
                return new ExecutionOutcome.Success(result);
            }
        }
    }

    /**
     * 路径 B：要全量，游标翻页。
     */
    private ExecutionOutcome executePaged(Connection connection, GuardedSql guardedSql,
                                          SqlExecutionRequest request, long started,
                                          String sqlHash) throws SQLException {
        CursorPager.PagingResult paging = pager.fetch(connection, guardedSql);
        log.info("取数完成（全量） runId={} stepId={} pages={} rows={} completed={} elapsedMs={}",
                request.runId(), request.stepId(), paging.pages(), paging.rows().size(),
                paging.completed(), elapsed(started));
        return new ExecutionOutcome.Success(new QueryResult(paging.columns(), paging.rows(),
                paging.rows().size(), !paging.completed(), paging.completed(), paging.pages(),
                elapsed(started), sqlHash));
    }

    /**
     * 路径 C：没写 LIMIT、也没声明全量。先探测，超页就要模型补声明。
     */
    private ExecutionOutcome executeProbe(Connection connection, GuardedSql guardedSql,
                                          long started, String sqlHash) throws SQLException {
        int probe = limits.pageSize();
        try (PreparedStatement statement = connection.prepareStatement(guardedSql.sql())) {
            statement.setQueryTimeout(limits.timeoutSeconds());
            statement.setMaxRows(probe + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                QueryResult result = readAll(resultSet, probe + 1, started, sqlHash);
                if (result.rowCount() > probe) {
                    log.info("取数需要模型补声明 hash={} probe={}", sqlHash.substring(0, 8), probe);
                    return new ExecutionOutcome.NeedsDeclaration(needsDeclarationMessage());
                }
                return new ExecutionOutcome.Success(result);
            }
        }
    }

    // ==================== 读取与错误 ====================

    /**
     * 读结果：列元数据 + 最多 maxRows 行。
     */
    private QueryResult readAll(ResultSet resultSet, int maxRows, long started,
                                String sqlHash) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        int count = meta.getColumnCount();
        int[] types = new int[count + 1];
        List<SqlQueryColumn> columns = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            types[i] = meta.getColumnType(i);
            columns.add(new SqlQueryColumn(meta.getColumnName(i), meta.getColumnLabel(i), types[i],
                    meta.getColumnTypeName(i), meta.isNullable(i) != ResultSetMetaData.columnNoNulls));
        }
        List<List<Object>> rows = new ArrayList<>();
        while (resultSet.next() && rows.size() < maxRows) {
            List<Object> row = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) {
                row.add(JdbcValueMapper.read(resultSet, i, types[i]));
            }
            rows.add(row);
        }
        return new QueryResult(columns, rows, rows.size(), false, true, 1, elapsed(started), sqlHash);
    }

    /**
     * 错误分类 + 双消息（模型能改的、用户能看的）。
     */
    private ExecutionOutcome failure(SQLException error, GuardedSql guardedSql,
                                     SqlExecutionRequest request) {
        SqlErrorKind kind = classify(error);
        String detail = safeDetail(error.getMessage());
        String modelMessage = kind == SqlErrorKind.SYNTAX
                ? kind.userMessage() + "：" + detail
                : kind.userMessage();
        if (kind == SqlErrorKind.WALL_REJECTED) {
            // 守卫放过、墙拦住：说明守卫有缺口，要报警而不是让模型重试
            log.warn("wall 拦下了守卫放行的 SQL runId={} stepId={} sql={}",
                    request.runId(), request.stepId(), guardedSql.sql());
        } else {
            log.warn("取数失败 runId={} stepId={} kind={} detail={}",
                    request.runId(), request.stepId(), kind, detail);
        }
        return new ExecutionOutcome.Failed(kind, modelMessage, kind.userMessage());
    }

    /**
     * 错误分类：先认错误码，再认 SQLState，最后按异常类型兜底。
     *
     * <p>MySQL 的权限错误（1044/1142）SQLState 也是 42000，会被驱动包装成
     * {@code SQLSyntaxErrorException}，所以错误码必须排在异常类型前面。</p>
     */
    private SqlErrorKind classify(SQLException error) {
        if (error instanceof SQLTimeoutException) {
            return SqlErrorKind.TIMEOUT;
        }
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.contains("WallFilter") || message.contains("sql injection violation")) {
            return SqlErrorKind.WALL_REJECTED;
        }
        int code = error.getErrorCode();
        if (code == 1044 || code == 1045 || code == 1142) {
            return SqlErrorKind.PERMISSION;
        }
        String state = error.getSQLState();
        if (state != null && state.startsWith("28")) {
            return SqlErrorKind.PERMISSION;
        }
        if (state != null && state.startsWith("08")) {
            return SqlErrorKind.CONNECTION;
        }
        if (error instanceof SQLSyntaxErrorException) {
            return SqlErrorKind.SYNTAX;
        }
        return SqlErrorKind.UNKNOWN;
    }

    /**
     * 模型没说清要多少行时的提示。
     */
    private String needsDeclarationMessage() {
        return "查询结果超过 " + limits.pageSize() + " 行。"
                + "如果你要的是全部数据，请把「需要全量」标记为 true，并且不要写 LIMIT；"
                + "如果你要的是固定条数，请在查询末尾拼上 LIMIT（例如 LIMIT 20）。";
    }

    private long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String safeDetail(String message) {
        if (message == null) {
            return "";
        }
        String text = message.replaceAll("[\\s]+", " ").trim();
        return text.length() <= MAX_ERROR_DETAIL ? text : text.substring(0, MAX_ERROR_DETAIL) + "…";
    }

    private static String hash(String sql) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(sql.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16));
                hex.append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    /**
     * 连接身份信息：自检与审计用。
     *
     * @param user          当前会话用户（含主机名）
     * @param database      当前库名
     * @param serverVersion 服务端版本
     */
    public record ConnectionInfo(String user, String database, String serverVersion) {
    }
}