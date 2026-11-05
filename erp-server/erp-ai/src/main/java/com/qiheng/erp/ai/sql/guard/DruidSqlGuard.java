package com.qiheng.erp.ai.sql.guard;

import com.qiheng.erp.ai.sql.guard.model.GuardResult;
import com.qiheng.erp.ai.sql.guard.model.GuardedSql;
import com.qiheng.erp.ai.sql.guard.model.SqlGuardLimits;
import com.qiheng.erp.ai.sql.guard.model.SqlGuardRejectCode;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLOrderBy;
import com.alibaba.druid.sql.ast.SQLOrderingSpecification;
import com.alibaba.druid.sql.ast.SQLSetQuantifier;
import com.alibaba.druid.sql.ast.SQLLimit;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAggregateExpr;
import com.alibaba.druid.sql.ast.expr.SQLAllColumnExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLIntegerExpr;
import com.alibaba.druid.sql.ast.expr.SQLMethodInvokeExpr;
import com.alibaba.druid.sql.ast.expr.SQLNumberExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.expr.SQLVariantRefExpr;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectOrderByItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;
import com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter;
import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * SQL 守卫的 MySQL 实现：用 Druid 的解析器做结构判定。
 *
 * <p><b>为什么是 Druid 而不是 JSqlParser</b>：JSqlParser 5.2 的 AST 不建模
 * {@code FOR UPDATE}（只有 {@code FOR UPDATE OF t}）、{@code LOCK IN SHARE MODE}、
 * {@code SKIP LOCKED}、{@code INTO OUTFILE}；Druid 是 MySQL 方言解析器，
 * 这些子句都在 AST 里，而且 AI 取数池本来就用它，不新增依赖。</p>
 *
 * <p><b>检查顺序</b>：先解析、再结构、再权限、再翻页前提。一次只返回第一个问题——
 * 一次甩五个错误给模型，它会改乱；一次一个，改一次就好。</p>
 *
 * <p><b>不做的事</b>：不改写 SQL（LIMIT 不钳制、ORDER BY 不补），不改写是执行器的活；
 * 不拦 {@code OUTFILE}/{@code DUMPFILE}（wall 过滤器已关掉这两项，第二道兜底）。</p>
 *
 * @author Li
 * @since 2026-10-25
 */
@Component
public class DruidSqlGuard implements SqlGuard {

    /** 危险函数：ERP 业务查询一个都用不到，出现就是试探。
     *<p> SLEEP — 时间盲注攻击
     *<p> BENCHMARK — 拒绝服务攻击
     *<p> LOAD_FILE — 读取服务器文件
     *<p> EXTRACTVALUE/UPDATEXML — 报错注入
     * */
    private static final Set<String> DANGEROUS_FUNCTIONS = Set.of(
            "SLEEP", "BENCHMARK", "LOAD_FILE", "GET_LOCK", "RELEASE_LOCK",
            "MASTER_POS_WAIT", "EXTRACTVALUE", "UPDATEXML");

    /** 系统库：不登记、也不允许查询。 */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema", "mysql", "performance_schema", "sys");

    /** 当前库名：SchemaRenderer 里写死的就是这个。 */
    private static final String CURRENT_SCHEMA = "erp";

    /** 无参时间函数在 AST 里可能是标识符，不能当成列名去查白名单。
     *<p> CURRENT_TIME — 当前时间
     *<p> CURRENT_TIMESTAMP — 当前时间戳
     *<p> CURRENT_USER — 当前用户
     *<p> LOCALTIME — 本地时间
     *<p> LOCALTIMESTAMP — 本地时间戳
     *<p> UTC_DATE — UTC 时间
     *<p> UTC_TIME — UTC 时间
     *<p> UTC_TIMESTAMP — UTC 时间戳
     * */
    private static final Set<String> SAFE_KEYWORDS = Set.of(
            "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER",
            "LOCALTIME", "LOCALTIMESTAMP", "UTC_DATE", "UTC_TIME", "UTC_TIMESTAMP");

    /** 软删除列名。 */
    private static final String LOGICAL_DELETE_COLUMN = "deleted";

    @Override
    public GuardResult check(String sql, AuthorizedSchemaSnapshot snapshot, SqlGuardLimits limits,
                             boolean needFullData) {
        if (sql == null || sql.isBlank()) {
            return reject(SqlGuardRejectCode.PARSE_FAILED);
        }
        List<SQLStatement> statements;
        // 解析：失败即拒绝（解析不了 = 不知道它要干什么）
        try {
            statements = SQLUtils.parseStatements(sql, DbType.mysql);
        } catch (RuntimeException error) {
            return reject(SqlGuardRejectCode.PARSE_FAILED);
        }
        // 恰好一条语句（防 "SELECT 1; DROP TABLE x" 这种夹带）
        if (statements.size() != 1) {
            return reject(SqlGuardRejectCode.MULTI_STATEMENT);
        }
        // 必须是 SELECT 语句
        if (!(statements.getFirst() instanceof SQLSelectStatement selectStatement)) {
            return reject(SqlGuardRejectCode.NOT_SELECT);
        }
        SQLSelect select = selectStatement.getSelect();
        if (select == null) {
            return reject(SqlGuardRejectCode.NOT_SELECT);
        }
        // 不支持 UNION 查询
        if (select.getQuery() instanceof SQLUnionQuery) {
            return reject(SqlGuardRejectCode.UNION_NOT_SUPPORTED);
        }
        SQLSelectQueryBlock block = select.getQueryBlock();
        if (block == null) {
            return reject(SqlGuardRejectCode.NOT_SELECT);
        }
        // 不支持注释
        if (containsComment(sql)) {
            return reject(SqlGuardRejectCode.COMMENT);
        }
        // 一张表都没有 = 纯常量查询，拒
        TableScope tables = collectTables(select);
        if (tables.names().isEmpty()) {
            return reject(SqlGuardRejectCode.NO_TABLE);
        }

        // 变量 + 危险函数（一次遍历拿齐）
        Scan scan = scan(select);
        if (scan.variable() != null) {
            return reject(SqlGuardRejectCode.VARIABLE_NOT_ALLOWED);
        }
        if (scan.dangerousFunction() != null) {
            return reject(SqlGuardRejectCode.DANGEROUS_FUNCTION,
                    Map.of("function", scan.dangerousFunction()));
        }
        // 加锁子句检查
        if (block.isForUpdate() || block.isForShare() || block.isSkipLocked()
                || containsLockInShareMode(sql)) {
            return reject(SqlGuardRejectCode.DANGEROUS_CLAUSE);
        }
        // SELECT * 棒绝
        if (containsSelectStar(select)) {
            return reject(SqlGuardRejectCode.SELECT_STAR);
        }
        // 库权限检查,SQL 带的库前缀是否是 erp,表名是否是系统库
        GuardResult schemaViolation = checkSchemas(tables);
        if (schemaViolation != null) {
            return schemaViolation;
        }
        // 表权限检查
        Set<String> allowedTables = snapshot.tableNames();
        for (String table : tables.names()) {
            if (!allowedTables.contains(table)) {
                return reject(SqlGuardRejectCode.TABLE_NOT_ALLOWED, Map.of(
                        "table", table,
                        "tables", allowedTables.isEmpty() ? "（无）" : String.join(", ", allowedTables)));
            }
        }
        // LIMIT 检查
        Integer limit = null;
        SQLLimit sqlLimit = block.getLimit();
        if (sqlLimit != null) {
            limit = intLiteral(sqlLimit.getRowCount());
            if (limit == null || limit > limits.maxTotalRows()) {
                return reject(SqlGuardRejectCode.LIMIT_TOO_LARGE, Map.of(
                        "maxRows", String.valueOf(limits.maxTotalRows()),
                        "limit", sqlLimit.getRowCount() == null ? "?" : sqlLimit.getRowCount().toString()));
            }
        }
        if (needFullData && sqlLimit != null) {
            return reject(SqlGuardRejectCode.FULL_AND_LIMIT_CONFLICT);
        }

        // 多表必须按表别名检查列；自连接虽然只有一个物理表，也按多表处理。
        if (tables.names().size() > 1 || block.getFrom() instanceof SQLJoinTableSource) {
            GuardResult columnViolation = checkJoinColumns(select, block, snapshot);
            if (columnViolation != null) {
                return columnViolation;
            }
        } else {
            String table = tables.names().iterator().next();
            GuardResult columnViolation = checkColumns(block, select, tables, table, snapshot);
            if (columnViolation != null) {
                return columnViolation;
            }
        }
        // 软删除检查
        GuardResult softDeleteViolation = checkSoftDelete(select, snapshot);
        if (softDeleteViolation != null) {
            return softDeleteViolation;
        }
        // 全量模式额外检查
        List<GuardedSql.SortKey> sortKeys = new ArrayList<>();
        String cursorTable = null;
        String cursorPrimaryKey = null;
        var executionStrategy = GuardedSql.ExecutionStrategy.BOUNDED;
        if (needFullData) {
            List<SQLSelectQueryBlock> blocks = new ArrayList<>();
            collectBlocks(select, blocks);
            // 仅单表明细能安全追加游标，其他查询保留原 SQL 并探测结果上限。
            if (tables.names().size() == 1 && !(block.getFrom() instanceof SQLJoinTableSource)
                    && block.getDistionOption() != SQLSetQuantifier.DISTINCT
                    && block.getGroupBy() == null && !hasAggregate(block)
                    && select.getWithSubQuery() == null && blocks.size() == 1) {
                cursorTable = tables.names().iterator().next();
                cursorPrimaryKey = snapshot.primaryKeyOf(cursorTable);
                GuardResult sortViolation = checkSortKeys(block, tables, cursorTable, snapshot, sortKeys);
                if (sortViolation instanceof GuardResult.Rejected rejected
                        && rejected.code() == SqlGuardRejectCode.COLUMN_NOT_ALLOWED) {
                    return sortViolation;
                }
                if (cursorPrimaryKey != null && sortViolation == null) {
                    executionStrategy = GuardedSql.ExecutionStrategy.CURSOR;
                } else {
                    // 游标条件不足不影响原 SQL 有界读取；不能保留未完成的游标配置。
                    sortKeys.clear();
                    cursorTable = null;
                    cursorPrimaryKey = null;
                }
            }
        }

        return new GuardResult.Ok(new GuardedSql(sql, tables.names(), limit,
                List.copyOf(sortKeys), cursorTable, cursorPrimaryKey, executionStrategy));
    }

    // ==================== 各条规则的实现 ====================

    /**
     * 多表查询只支持直接表 JOIN，逐个核对表别名和字段。
     * 嵌套查询涉及作用域和派生列的血缘，无法可靠判定时不放行。
     */
    private GuardResult checkJoinColumns(SQLSelect select, SQLSelectQueryBlock block,
                                         AuthorizedSchemaSnapshot snapshot) {
        List<SQLSelectQueryBlock> blocks = new ArrayList<>();
        collectBlocks(select, blocks);
        if (select.getWithSubQuery() != null || blocks.size() != 1) {
            return reject(SqlGuardRejectCode.JOIN_COMPLEX_NOT_SUPPORTED);
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        if (!collectJoinAliases(block.getFrom(), aliases)) {
            return reject(SqlGuardRejectCode.JOIN_COMPLEX_NOT_SUPPORTED);
        }
        Set<String> outputAliases = new LinkedHashSet<>();
        for (SQLSelectItem item : block.getSelectList()) {
            addSkip(outputAliases, item.getAlias());
        }
        List<GuardResult> violations = new ArrayList<>();
        block.accept(new SQLASTVisitorAdapter() {
            @Override
            public boolean visit(SQLExprTableSource source) {
                // FROM 中的标识符是表名，不是列引用。
                return false;
            }

            @Override
            public boolean visit(SQLPropertyExpr expression) {
                String owner = expression.getOwner() == null ? "" : normalize(expression.getOwner().toString());
                String table = aliases.get(owner.toLowerCase(Locale.ROOT));
                String column = normalize(expression.getName());
                if (table == null) {
                    violations.add(reject(SqlGuardRejectCode.JOIN_COLUMN_UNRESOLVED, Map.of("column", expression.toString())));
                } else if (!snapshot.hasColumn(table, column)) {
                    violations.add(reject(SqlGuardRejectCode.COLUMN_NOT_ALLOWED, Map.of(
                            "column", owner + "." + column,
                            "columns", String.join(", ", snapshot.columnNames(table)))));
                }
                // 不再遍历 owner，避免把表别名当作未限定的列。
                return false;
            }

            @Override
            public boolean visit(SQLIdentifierExpr expression) {
                String name = normalize(expression.getName());
                if (SAFE_KEYWORDS.contains(name.toUpperCase(Locale.ROOT))) {
                    return false;
                }
                // 仅 ORDER BY 的单独输出别名可省略表限定；SELECT 别名不能掩盖隐藏列。
                if (expression.getParent() instanceof SQLSelectOrderByItem
                        && outputAliases.contains(name.toLowerCase(Locale.ROOT))) {
                    return false;
                }
                violations.add(reject(SqlGuardRejectCode.JOIN_COLUMN_UNRESOLVED, Map.of("column", name)));
                return false;
            }
        });
        return violations.isEmpty() ? null : violations.getFirst();
    }

    /** 仅解析直接表和 JOIN，避免把派生表当作已授权物理表。 */
    private boolean collectJoinAliases(SQLTableSource source, Map<String, String> aliases) {
        if (source instanceof SQLJoinTableSource join) {
            // NATURAL/USING 会隐式使用未授权列；外连接的 ON 过滤无法约束保留侧。
            if (join.isNatural() || !join.getUsing().isEmpty()
                    || !(join.getJoinType() == SQLJoinTableSource.JoinType.JOIN
                    || join.getJoinType() == SQLJoinTableSource.JoinType.INNER_JOIN
                    || join.getJoinType() == SQLJoinTableSource.JoinType.CROSS_JOIN
                    || join.getJoinType() == SQLJoinTableSource.JoinType.COMMA)) {
                return false;
            }
            return collectJoinAliases(join.getLeft(), aliases) && collectJoinAliases(join.getRight(), aliases);
        }
        if (!(source instanceof SQLExprTableSource table) || table.getName() == null) {
            return false;
        }
        String name = normalize(table.getName().toString());
        int dot = name.indexOf('.');
        String tableName = dot < 0 ? name : name.substring(dot + 1);
        String alias = normalize(table.getAlias());
        String key = (alias.isEmpty() ? name : alias).toLowerCase(Locale.ROOT);
        return aliases.putIfAbsent(key, tableName) == null;
    }

    /**
     * 收表：收集 SQL 里引用的真实表，去掉库前缀，排除 CTE 别名。
     */
    private TableScope collectTables(SQLSelect select) {
        Set<String> names = new LinkedHashSet<>();
        Set<String> schemas = new LinkedHashSet<>();
        Set<String> tableAliases = new LinkedHashSet<>();
        select.accept(new SQLASTVisitorAdapter() {
            @Override
            public boolean visit(SQLExprTableSource source) {
                SQLName name = source.getName();
                if (name == null) {
                    return true;
                }
                String text = normalize(name.toString());
                String schema = null;
                String table = text;
                int dot = text.indexOf('.');
                if (dot > 0) {
                    schema = text.substring(0, dot);
                    table = text.substring(dot + 1);
                }
                names.add(table);
                if (schema != null) {
                    schemas.add(schema);
                }
                tableAliases.add(table);
                String alias = normalize(source.getAlias());
                if (!alias.isEmpty()) {
                    tableAliases.add(alias);
                }
                return true;
            }
        });
        SQLWithSubqueryClause with = select.getWithSubQuery();
        if (with != null && with.getEntries() != null) {
            for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                names.remove(normalize(entry.getAlias()));
            }
        }
        return new TableScope(names, schemas, tableAliases);
    }

    /**
     * 变量与危险函数：一次遍历拿齐。
     */
    private Scan scan(SQLSelect select) {
        Scan scan = new Scan();
        select.accept(new SQLASTVisitorAdapter() {
            @Override
            public boolean visit(SQLVariantRefExpr expression) {
                scan.variable = expression.getName();
                return false;
            }

            @Override
            public boolean visit(SQLMethodInvokeExpr expression) {
                checkFunction(expression.getMethodName());
                return true;
            }

            @Override
            public boolean visit(SQLAggregateExpr expression) {
                checkFunction(expression.getMethodName());
                return true;
            }

            private void checkFunction(String name) {
                if (name != null && DANGEROUS_FUNCTIONS.contains(name.toUpperCase(Locale.ROOT))) {
                    scan.dangerousFunction = name;
                }
            }
        });
        return scan;
    }

    /**
     * 表所属库：只允许当前库，系统库单独给码。
     */
    private GuardResult checkSchemas(TableScope tables) {
        for (String schema : tables.schemas()) {
            if (!CURRENT_SCHEMA.equalsIgnoreCase(schema)) {
                return reject(SqlGuardRejectCode.SYSTEM_SCHEMA);
            }
        }
        for (String table : tables.names()) {
            if (SYSTEM_SCHEMAS.contains(table.toLowerCase(Locale.ROOT))) {
                return reject(SqlGuardRejectCode.SYSTEM_SCHEMA);
            }
        }
        return null;
    }

    /**
     * SELECT *：检查**每一个**查询块，不只顶层。
     *
     * <p>子查询或 CTE 里的 {@code SELECT *} 会把被屏蔽的列带进中间结果，
     * 顶层再按列名取就能绕开列白名单——所以任何一层出现星号都拒。
     * {@code COUNT(*)} 的星号在函数参数里，不是顶层 select item，不会误杀。</p>
     */
    private boolean containsSelectStar(SQLSelect select) {
        boolean[] found = {false};
        select.accept(new SQLASTVisitorAdapter() {
            @Override
            public boolean visit(SQLSelectQueryBlock block) {
                if (hasSelectStar(block)) {
                    found[0] = true;
                    return false;
                }
                return true;
            }
        });
        return found[0];
    }

    /**
     * 单个查询块的顶层 select item 是否有星号。
     */
    private boolean hasSelectStar(SQLSelectQueryBlock block) {
        for (SQLSelectItem item : block.getSelectList()) {
            SQLExpr expression = item.getExpr();
            if (expression instanceof SQLAllColumnExpr) {
                return true;
            }
            if (expression instanceof SQLPropertyExpr property && "*".equals(property.getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 列级白名单：只对单表查询做。
     *
     * <p>跳过表源和无参时间函数；输出别名只在 ORDER BY 中使用，避免掩盖隐藏列。</p>
     */
    private GuardResult checkColumns(SQLSelectQueryBlock block, SQLSelect select, TableScope tables,
                                     String table, AuthorizedSchemaSnapshot snapshot) {
        Set<String> skip = new LinkedHashSet<>();
        Set<String> outputAliases = new LinkedHashSet<>();
        for (SQLSelectItem item : block.getSelectList()) {
            addSkip(outputAliases, item.getAlias());
            addSkip(outputAliases, item.getAlias2());
        }
        SAFE_KEYWORDS.forEach(keyword -> skip.add(keyword.toLowerCase(Locale.ROOT)));

        List<String> unknown = new ArrayList<>();
        select.accept(new SQLASTVisitorAdapter() {
            @Override
            public boolean visit(SQLExprTableSource source) {
                return false;
            }

            @Override
            public boolean visit(SQLIdentifierExpr expression) {
                // 别名只在排序中有效，不能用隐藏列同名别名绕过 SELECT 的列检查。
                if (expression.getParent() instanceof SQLSelectOrderByItem
                        && outputAliases.contains(normalize(expression.getName()).toLowerCase(Locale.ROOT))) {
                    return false;
                }
                check(expression.getName());
                return true;
            }

            @Override
            public boolean visit(SQLPropertyExpr expression) {
                String owner = expression.getOwner() == null ? "" : normalize(expression.getOwner().toString());
                boolean knownOwner = tables.tableAliases().stream().anyMatch(alias -> alias.equalsIgnoreCase(owner))
                        || (CURRENT_SCHEMA + "." + table).equalsIgnoreCase(owner);
                if (owner.isEmpty() || knownOwner) {
                    check(expression.getName());
                } else {
                    // 无法归属到已授权表的限定列不能跳过，否则三段限定名可能绕过列权限。
                    unknown.add(expression.toString());
                }
                return false;
            }

            private void check(String rawName) {
                String column = normalize(rawName);
                if (column.isEmpty() || "*".equals(column)) {
                    return;
                }
                if (skip.contains(column.toLowerCase(Locale.ROOT))) {
                    return;
                }
                if (snapshot.hasColumn(table, column)) {
                    return;
                }
                unknown.add(column);
            }
        });
        if (unknown.isEmpty()) {
            return null;
        }
        return reject(SqlGuardRejectCode.COLUMN_NOT_ALLOWED, Map.of(
                "column", unknown.getFirst(),
                "columns", String.join(", ", snapshot.columnNames(table))));
    }

    /**
     * 软删除：按查询块分别检查，且要求过滤条件是**必须成立**的。
     *
     * <p>两条都是被真实绕过的口子，别改回去：</p>
     * <ul>
     *   <li><b>不能只看"出现过"</b>：{@code deleted = 0 OR id = 1} 里条件出现过，
     *       但 OR 的另一支照样能带出已删除的行。必须做布尔结构判定。</li>
     *   <li><b>不能只扫一遍整棵树</b>：外层没过滤、子查询里过滤了，旧实现会误判成已过滤。
     *       每个查询块只看自己的 FROM 和自己的 WHERE/ON。</li>
     * </ul>
     */
    private GuardResult checkSoftDelete(SQLSelect select, AuthorizedSchemaSnapshot snapshot) {
        List<SQLSelectQueryBlock> blocks = new ArrayList<>();
        collectBlocks(select, blocks);
        for (SQLSelectQueryBlock block : blocks) {
            GuardResult violation = checkSoftDeleteInBlock(block, snapshot);
            if (violation != null) {
                return violation;
            }
        }
        return null;
    }

    /**
     * 收集所有查询块：主查询、子查询、CTE。
     */
    private void collectBlocks(SQLSelect select, List<SQLSelectQueryBlock> blocks) {
        if (select == null) {
            return;
        }
        select.accept(new SQLASTVisitorAdapter() {
            @Override
            public boolean visit(SQLSelectQueryBlock block) {
                if (!blocks.contains(block)) {
                    blocks.add(block);
                }
                return true;
            }
        });
        SQLWithSubqueryClause with = select.getWithSubQuery();
        if (with != null && with.getEntries() != null) {
            for (SQLWithSubqueryClause.Entry entry : with.getEntries()) {
                collectBlocks(entry.getSubQuery(), blocks);
            }
        }
    }

    /**
     * 单个查询块：每张有软删除列的表，其 {@code deleted = 0} 必须被 WHERE 或 JOIN ON 强制。
     */
    private GuardResult checkSoftDeleteInBlock(SQLSelectQueryBlock block,
                                               AuthorizedSchemaSnapshot snapshot) {
        List<TableRef> refs = new ArrayList<>();
        List<SQLExpr> conditions = new ArrayList<>();
        if (block.getWhere() != null) {
            conditions.add(block.getWhere());
        }
        collectFrom(block.getFrom(), refs, conditions);
        if (refs.isEmpty()) {
            return null;
        }
        boolean singleTable = refs.size() == 1;
        for (TableRef ref : refs) {
            if (!snapshot.hasLogicalDelete(ref.name())) {
                continue;
            }
            boolean filtered = false;
            for (SQLExpr condition : conditions) {
                if (impliesDeletedFilter(condition, ref, singleTable)) {
                    filtered = true;
                    break;
                }
            }
            if (!filtered) {
                return reject(SqlGuardRejectCode.SOFT_DELETE_FILTER_MISSING,
                        Map.of("table", ref.name()));
            }
        }
        return null;
    }

    /**
     * 收集本块直接引用的表与约束条件（WHERE + 各 JOIN 的 ON）。
     *
     * <p>子查询表源不在这里展开：它内部是另一个查询块，由 {@link #collectBlocks} 单独检查。</p>
     */
    private void collectFrom(SQLTableSource source, List<TableRef> refs, List<SQLExpr> conditions) {
        switch (source) {
            case SQLExprTableSource table -> {
                String text = normalize(table.getName() == null ? "" : table.getName().toString());
                String name = text;
                int dot = text.indexOf('.');
                if (dot > 0) {
                    name = text.substring(dot + 1);
                }
                refs.add(new TableRef(name, normalize(table.getAlias())));
            }
            case SQLJoinTableSource join -> {
                collectFrom(join.getLeft(), refs, conditions);
                collectFrom(join.getRight(), refs, conditions);
                if (join.getCondition() != null) {
                    conditions.add(join.getCondition());
                }
            }
            case null, default -> {
            }
        }
    }

    /**
     * 判断一个条件表达式是否**必然**要求该表的 {@code deleted = 0}。
     *
     * <p>只做保守的语法判定，宁可多拒也不放行：</p>
     * <ul>
     *   <li>{@code A AND B}：任一分支强制即可（{@code deleted=0 AND x} 成立）</li>
     *   <li>{@code A OR B}：两边都必须强制（{@code deleted=0 OR x} 不成立）</li>
     *   <li>比较：只有 {@code deleted = 0} 才算，{@code <> 1} / {@code NOT ...} 一律不认</li>
     * </ul>
     */
    private boolean impliesDeletedFilter(SQLExpr expression, TableRef ref, boolean singleTable) {
        if (!(expression instanceof SQLBinaryOpExpr binary)) {
            return false;
        }
        SQLBinaryOperator operator = binary.getOperator();
        if (operator == SQLBinaryOperator.BooleanAnd) {
            return impliesDeletedFilter(binary.getLeft(), ref, singleTable)
                    || impliesDeletedFilter(binary.getRight(), ref, singleTable);
        }
        if (operator == SQLBinaryOperator.BooleanOr) {
            return impliesDeletedFilter(binary.getLeft(), ref, singleTable)
                    && impliesDeletedFilter(binary.getRight(), ref, singleTable);
        }
        if (operator == SQLBinaryOperator.Equality) {
            return isDeletedZero(binary.getLeft(), binary.getRight(), ref, singleTable)
                    || isDeletedZero(binary.getRight(), binary.getLeft(), ref, singleTable);
        }
        return false;
    }

    /**
     * 判断一边是 {@code deleted} 列、另一边是数字 0，且列归属到这张表。
     */
    private boolean isDeletedZero(SQLExpr columnSide, SQLExpr valueSide, TableRef ref,
                                  boolean singleTable) {
        String column = null;
        String owner = null;
        if (columnSide instanceof SQLIdentifierExpr identifier) {
            column = normalize(identifier.getName());
        } else if (columnSide instanceof SQLPropertyExpr property) {
            column = normalize(property.getName());
            owner = property.getOwner() == null ? null : normalize(property.getOwner().toString());
        }
        if (!LOGICAL_DELETE_COLUMN.equalsIgnoreCase(column)) {
            return false;
        }
        if (!(valueSide instanceof SQLIntegerExpr integer) || integer.getNumber().intValue() != 0) {
            return false;
        }
        if (owner != null) {
            return owner.equalsIgnoreCase(ref.alias()) || owner.equalsIgnoreCase(ref.name());
        }
        // 无限定的 deleted 只在单表时算数；多表时指代不明，不在这里猜
        return singleTable;
    }

    /**
     * 取全量的排序前提：排序键必须是普通列、必须非空。
     */
    private GuardResult checkSortKeys(SQLSelectQueryBlock block, TableScope tables, String table,
                                      AuthorizedSchemaSnapshot snapshot,
                                      List<GuardedSql.SortKey> out) {
        SQLOrderBy orderBy = block.getOrderBy();
        if (orderBy == null || orderBy.getItems() == null) {
            return null;
        }
        GuardResult unsupported = null;
        for (SQLSelectOrderByItem item : orderBy.getItems()) {
            String column = resolveSortColumn(item.getExpr(), block, tables);
            if (column == null) {
                unsupported = reject(SqlGuardRejectCode.ORDER_BY_EXPRESSION_NOT_ALLOWED);
                continue;
            }
            AuthorizedSchemaSnapshot.Column definition = snapshot.findColumn(table, column);
            if (definition == null) {
                return reject(SqlGuardRejectCode.COLUMN_NOT_ALLOWED, Map.of(
                        "column", column,
                        "columns", String.join(", ", snapshot.columnNames(table))));
            }
            if (definition.nullable()) {
                unsupported = reject(SqlGuardRejectCode.SORT_KEY_NULLABLE, Map.of("column", column));
                continue;
            }
            out.add(new GuardedSql.SortKey(column,
                    SQLOrderingSpecification.DESC.equals(item.getType())));
        }
        return unsupported;
    }

    /**
     * 把排序项解析成列名：支持 {@code col}、{@code t.col} 和 select 别名。
     */
    private String resolveSortColumn(SQLExpr expression, SQLSelectQueryBlock block, TableScope tables) {
        if (expression instanceof SQLPropertyExpr property) {
            String owner = property.getOwner() == null ? "" : normalize(property.getOwner().toString());
            if (tables.tableAliases().contains(owner)) {
                return normalize(property.getName());
            }
            return null;
        }
        if (expression instanceof SQLIdentifierExpr identifier) {
            String name = normalize(identifier.getName());
            for (SQLSelectItem item : block.getSelectList()) {
                String alias = normalize(item.getAlias());
                if (!alias.isEmpty() && alias.equalsIgnoreCase(name)) {
                    SQLExpr aliased = item.getExpr();
                    if (aliased instanceof SQLIdentifierExpr inner) {
                        return normalize(inner.getName());
                    }
                    if (aliased instanceof SQLPropertyExpr inner) {
                        return normalize(inner.getName());
                    }
                    return null;
                }
            }
            return name;
        }
        return null;
    }

    /**
     * 聚合或窗口查询不能通过明细游标改写。
     *
     * <p>HAVING 挂在 GROUP BY 子句上，调用方已先判过 {@code getGroupBy() != null}，
     * 这里只看 SELECT 列表里的聚合函数。</p>
     */
    private boolean hasAggregate(SQLSelectQueryBlock block) {
        boolean[] found = {false};
        SQLASTVisitorAdapter visitor = new SQLASTVisitorAdapter() {
            @Override
            public boolean visit(SQLAggregateExpr expression) {
                found[0] = true;
                return false;
            }
            @Override
            public boolean visit(com.alibaba.druid.sql.ast.SQLOver expression) {
                found[0] = true;
                return false;
            }
        };
        for (SQLSelectItem item : block.getSelectList()) {
            item.getExpr().accept(visitor);
        }
        if (block.getOrderBy() != null) {
            block.getOrderBy().accept(visitor);
        }
        return found[0];
    }

    // ==================== 小工具 ====================

    private Integer intLiteral(SQLExpr expression) {
        if (expression instanceof SQLIntegerExpr integer) {
            return integer.getNumber().intValue();
        }
        if (expression instanceof SQLNumberExpr number) {
            return number.getNumber().intValue();
        }
        return null;
    }

    /**
     * 注释扫描：先屏蔽字符串与反引号标识符，再在"代码区"里识别 {@code --}、{@code #}、{@code /*}。
     *
     * <p>不走 AST 是因为 Druid 默认会丢弃注释；文本扫描更直接，
     * 也不会把字符串里的 {@code #} 当注释。</p>
     */
    static boolean containsComment(String sql) {
        String code = maskLiterals(sql);
        if (code.contains("#") || code.contains("/*")) {
            return true;
        }
        for (int i = 0; i + 1 < code.length(); i++) {
            if (code.charAt(i) == '-' && code.charAt(i + 1) == '-'
                    && (i + 2 >= code.length() || Character.isWhitespace(code.charAt(i + 2)))) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code LOCK IN SHARE MODE} 的识别：Druid 1.2.28 只给 {@code FOR SHARE} 置了
     * {@code forShare} 标记，{@code LOCK IN SHARE MODE} 原样保留在输出里但没有标记，
     * 只能补一次带引号屏蔽的文本检查。
     */
    static boolean containsLockInShareMode(String sql) {
        return maskLiterals(sql).toUpperCase(Locale.ROOT).contains("LOCK IN SHARE MODE");
    }

    /**
     * 把字符串字面量与反引号标识符替换成空格，保留长度。
     *
     * <p>注释扫描与关键字扫描都要在"代码区"里做：字符串里的 {@code #} 不是注释，
     * 产品名里出现 {@code lock in share mode} 也不该被当成加锁子句。</p>
     */
    static String maskLiterals(String sql) {
        StringBuilder masked = new StringBuilder(sql.length());
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (inSingle || inDouble || inBacktick) {
                masked.append(' ');
                if (c == '\\' && i + 1 < sql.length()) {
                    masked.append(' ');
                    i++;
                } else if (inSingle && c == '\'') {
                    inSingle = false;
                } else if (inDouble && c == '"') {
                    inDouble = false;
                } else if (inBacktick && c == '`') {
                    inBacktick = false;
                }
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                masked.append(' ');
            } else if (c == '"') {
                inDouble = true;
                masked.append(' ');
            } else if (c == '`') {
                inBacktick = true;
                masked.append(' ');
            } else {
                masked.append(c);
            }
        }
        return masked.toString();
    }

    /**
     * 把别名添加到跳过列表（忽略大小写）。
     */
    private static void addSkip(Set<String> skip, String alias) {
        String normalized = normalize(alias);
        if (!normalized.isEmpty()) {
            skip.add(normalized.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * 把字符串值（如 {@code "a"`、{@code 'b'}）标准化为 {@code a}。
     */
    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("`", "").replace("\"", "").trim();
    }

    /**
     * 拒绝：拒绝码。
     */
    private GuardResult reject(SqlGuardRejectCode code) {
        return reject(code, Map.of());
    }

    /**
     * 拒绝：拒绝码与值(提供变量值)。
     */
    private GuardResult reject(SqlGuardRejectCode code, Map<String, String> values) {
        return new GuardResult.Rejected(code, code.render(values));
    }

    /**
     * 表范围：引用到的表、库、表名与别名。
     */
    private record TableScope(Set<String> names, Set<String> schemas, Set<String> tableAliases) {
    }

    /**
     * 查询块里直接引用的一张表：表名 + 别名（软删除条件要按别名归属）。
     */
    private record TableRef(String name, String alias) {
    }

    /**
     * 扫描结果：变量与危险函数。
     */
    private static final class Scan {
        private String variable;
        private String dangerousFunction;

        private String variable() {
            return variable;
        }

        private String dangerousFunction() {
            return dangerousFunction;
        }
    }
}
