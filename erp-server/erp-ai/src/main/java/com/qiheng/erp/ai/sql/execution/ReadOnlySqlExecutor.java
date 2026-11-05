package com.qiheng.erp.ai.sql.execution;

import com.qiheng.erp.ai.sql.guard.model.GuardedSql;
import com.qiheng.erp.ai.sql.execution.model.ExecutionOutcome;
import com.qiheng.erp.ai.sql.execution.model.QueryResult;
import com.qiheng.erp.ai.sql.execution.model.SqlErrorKind;
import com.qiheng.erp.ai.sql.execution.model.SqlExecutionRequest;
import com.qiheng.erp.ai.sql.execution.model.SqlExecutionLimits;
import com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn;
import com.qiheng.erp.ai.config.AiSqlEnabledCondition;
import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import com.qiheng.erp.ai.sql.pool.ReadOnlySqlPool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.util.ArrayList;
import java.util.List;

/**
 * AI 取数执行器：把守卫放行的 SQL 安全地跑完、把结果完整带回来。
 *
 * <p>连接池由 {@link ReadOnlySqlPool} 私有持有；本类只负责三条取数路径与错误分类。</p>
 *
 * <p>SQL 有 LIMIT 时读取限量结果；守卫选定游标时分页读取；其余查询原样执行并读取至系统总上限。</p>
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

    private final SqlGuardGapReporter guardGapReporter;

    @Autowired
    public ReadOnlySqlExecutor(AiSqlProperties properties, SqlGuardGapReporter guardGapReporter) {
        this(new ReadOnlySqlPool(properties.toSettings()),
                new SqlExecutionLimits(properties.pageSize(), properties.maxTotalRows(),
                        properties.maxPages(), properties.queryTimeoutSeconds()),
                guardGapReporter);
        log.info("AI 取数连接池已就绪 poolName={} maxActive={} 只读账号={}",
                properties.datasource().poolName(), properties.datasource().maxActive(),
                properties.datasource().username());
    }

    /**
     * 测试用：直接给池与上限。
     *
     * @param pool   只读池
     * @param limits 取数上限
     */
    ReadOnlySqlExecutor(ReadOnlySqlPool pool, SqlExecutionLimits limits,
                        SqlGuardGapReporter guardGapReporter) {
        this.pool = pool;
        this.limits = limits;
        this.pager = new CursorPager(limits);
        this.guardGapReporter = guardGapReporter;
    }

    /**
     * 执行一条守卫放行的 SQL。
     *
     * @param request 请求
     * @return 成功、超过取数上限、分页未完成或执行失败
     */
    public ExecutionOutcome execute(SqlExecutionRequest request) {
        GuardedSql guardedSql = request.guardedSql();
        long started = System.nanoTime();
        // 对 SQL 文本做 SHA-256 哈希，用于日志脱敏——日志里不打印完整 SQL（可能含业务数据），只打印哈希前 8 位
        String sqlHash = hash(guardedSql.sql());
        try (Connection connection = openConnection()) {
            if (guardedSql.limit() != null) {
                // 路径 A：SQL 自带 LIMIT
                return executeWithLimit(connection, guardedSql, started, sqlHash);
            }
            if (guardedSql.executionStrategy() == GuardedSql.ExecutionStrategy.CURSOR) {
                // 只对守卫确认可分页的单表明细改写 SQL。
                return executePaged(connection, guardedSql, request, started, sqlHash);
            }
            // 聚合、子查询及非游标结果按原 SQL 有界读取，不能拼接明细游标。
            return executeBounded(connection, guardedSql, started, sqlHash);
        } catch (SQLException error) {
            // SQL 异常 → 分类处理
            return failure(error, guardedSql, request, sqlHash);
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
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT CURRENT_USER(), DATABASE(), VERSION()");
             ResultSet resultSet = statement.executeQuery()) {
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
        // 预编译，多次执行更高效
        try (PreparedStatement statement = connection.prepareStatement(guardedSql.sql())) {
            // 设置查询超时时间
            statement.setQueryTimeout(limits.timeoutSeconds());
            // 限制返回行数，数据库仍可能扫描更多源数据。
            statement.setMaxRows(limit + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                QueryResult result = readAll(resultSet, limit + 1, started, sqlHash);
                if (result.rowCount() > limit) {
                    result = new QueryResult(result.columns(), result.rows().subList(0, limit), limit,
                            true, 1, result.elapsedMillis(), result.sqlHash());
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
        // 游标页：按游标翻页
        CursorPager.PagingResult paging = pager.fetch(connection, guardedSql);
        log.info("取数完成（全量） runId={} stepId={} pages={} rows={} completed={} elapsedMs={}",
                request.runId(), request.stepId(), paging.pages(), paging.rows().size(),
                paging.completed(), elapsed(started));
        return new ExecutionOutcome.Success(new QueryResult(paging.columns(), paging.rows(),
                paging.rows().size(), paging.completed(), paging.pages(),
                elapsed(started), sqlHash, paging.truncationReason()));
    }

    /**
     * 原样执行非游标 SQL；多取一行用于判断结果是否超过系统总上限。
     */
    private ExecutionOutcome executeBounded(Connection connection, GuardedSql guardedSql,
                                          long started, String sqlHash) throws SQLException {
        int maxRows = limits.maxTotalRows();
        try (PreparedStatement statement = connection.prepareStatement(guardedSql.sql())) {
            statement.setQueryTimeout(limits.timeoutSeconds());
            statement.setMaxRows(maxRows + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                QueryResult result = readAll(resultSet, maxRows + 1, started, sqlHash);
                if (result.rowCount() > maxRows) {
                    log.info("非游标取数超过总行数上限 hash={} maxRows={}", sqlHash.substring(0, 8), maxRows);
                    return new ExecutionOutcome.Success(new QueryResult(result.columns(),
                            result.rows().subList(0, maxRows), maxRows, false, 1,
                            result.elapsedMillis(), result.sqlHash(), "ROW_LIMIT"));
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
        // 1. 读取列元数据(ResultSetMetaData 提供结果集的列信息：列名、列类型、是否可空等
        ResultSetMetaData meta = resultSet.getMetaData();
        int count = meta.getColumnCount();
        // 1.1 读取每列的 JDBC 类型码
        int[] types = new int[count + 1];
        List<SqlQueryColumn> columns = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            types[i] = meta.getColumnType(i);
            columns.add(new SqlQueryColumn(meta.getColumnName(i),  // 列名（如 "price"）
                    meta.getColumnLabel(i), // 列标签（如 SELECT price AS p 中的 "p"）
                    types[i], // 列类型（如 "DECIMAL(10,2)"）
                    meta.getColumnTypeName(i), // JDBC 类型码(Types.VARCHAR、Types.INTEGER 等)
                    meta.isNullable(i) != ResultSetMetaData.columnNoNulls)); // 是否可空
        }
        // 2.读取行数据
        List<List<Object>> rows = new ArrayList<>();
        while (resultSet.next() && rows.size() < maxRows) {
            List<Object> row = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) {
                // 根据列类型安全地读取值
                row.add(JdbcValueMapper.read(resultSet, i, types[i]));
            }
            rows.add(row);
        }
        return new QueryResult(columns, rows, rows.size(), true, 1, elapsed(started), sqlHash);
    }

    /**
     * 错误分类 + 双消息（模型能改的、用户能看的）。
     */
    private ExecutionOutcome failure(SQLException error, GuardedSql guardedSql,
                                     SqlExecutionRequest request, String sqlHash) {
        // 1. 错误分类
        SqlErrorKind kind = classify(error);
        // 2. 截断错误详情,把错误信息压缩到 300 字符以内，防止超长信息
        String detail = safeDetail(error.getMessage());
        // 3. 给模型看的说明（可带数据库细节，能指导改写）
        // 语法错误 → 把具体错误信息告诉模型，让它修正 SQL（如 "SQL 语法错误：Unknown column 'pricee' in field list"）
        // 其他错误 → 只告诉类别，不暴露细节（如权限错误不告诉模型具体缺什么权限）
        String modelMessage = switch (kind) {
            // 语法错要把数据库原话给模型，它才能改对
            case SYNTAX -> kind.userMessage() + "：" + detail;
            // wall 拒因是英文，翻译成能直接照做的中文
            case WALL_REJECTED -> "查询被安全策略拦截：" + WallRejectionReasons.explain(error.getMessage());
            default -> kind.userMessage();
        };
        if (kind == SqlErrorKind.WALL_REJECTED) {
            // 守卫放过、墙拦住：说明守卫有缺口，要报警而不是让模型重试
            log.warn("wall 拦下了守卫放行的 SQL runId={} stepId={} sql={}",
                    request.runId(), request.stepId(), guardedSql.sql());
            guardGapReporter.reportWallRejected(request.runId(), request.stepId(), sqlHash, detail);
        } else if (kind == SqlErrorKind.AUTHENTICATION) {
            // 认证失败是配置问题，重试无意义，用 ERROR 级别让它进告警
            log.error("取数认证失败（检查只读账号配置） runId={} stepId={} detail={}",
                    request.runId(), request.stepId(), detail);
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
        // 认证失败：账号/口令/主机配置错了，不是"没权限查这张表"
        if (code == 1045) {
            return SqlErrorKind.AUTHENTICATION;
        }
        // 权限错误：1044=DB级, 1142=表级, 1143=列级, 1227=特定权限, 1370=存储过程
        if (code == 1044 || code == 1142 || code == 1143 || code == 1227 || code == 1370) {
            return SqlErrorKind.PERMISSION;
        }
        // SQLState 以 28 开头	ANSI 标准：认证/权限	PERMISSION
        String state = error.getSQLState();
        if (state != null && state.startsWith("28")) {
            return SqlErrorKind.PERMISSION;
        }
        // SQLState 以 08 开头	ANSI 标准：连接异常
        if (state != null && state.startsWith("08")) {
            return SqlErrorKind.CONNECTION;
        }
        // 语法错误
        if (error instanceof SQLSyntaxErrorException) {
            return SqlErrorKind.SYNTAX;
        }
        return SqlErrorKind.UNKNOWN;
    }

    /**
     * 执行耗时：单位毫秒。
     */
    private long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    /**
     * 安全的错误详情：去空格、截断。
     */
    private static String safeDetail(String message) {
        if (message == null) {
            return "";
        }
        // 1. 去空格
        String text = message.replaceAll("\\s+", " ").trim();
        // 2. 截断
        return text.length() <= MAX_ERROR_DETAIL ? text : text.substring(0, MAX_ERROR_DETAIL) + "…";
    }

    /**
     * SQL 哈希值：用于缓存与日志。
     * @param sql
     * @return
     */
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
     * @param user          当前会话用户（含主机名）
     * @param database      当前库名
     * @param serverVersion 服务端版本
     */
    public record ConnectionInfo(String user, String database, String serverVersion) {
    }
}
