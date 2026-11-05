package com.qiheng.erp.ai.sql.execution;

import com.qiheng.erp.ai.sql.execution.model.SqlExecutionLimits;
import com.qiheng.erp.ai.sql.guard.model.GuardedSql;
import com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn;
import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLExprImpl;
import com.alibaba.druid.sql.ast.SQLOrderBy;
import com.alibaba.druid.sql.ast.SQLOrderingSpecification;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 游标翻页：把"要全量"的查询拆成多页，且保证不重不漏。
 *
 * <p><b>为什么不用 OFFSET</b>：{@code LIMIT 200 OFFSET 4000} 要求数据库先扫过 4000 行再扔掉，
 * 越翻越慢；而且翻页期间数据一变，offset 就错位，会重会漏。游标定位的是"值"，不是"第几行"。</p>
 *
 * <p><b>游标怎么来</b>：排序键的值 + 主键的值。模型可能没 SELECT 这两列，所以执行器临时把它们
 * 注入成 {@code __cursor_N}，读完书签再剥掉——模型看到的列和它自己写的一模一样。</p>
 *
 * <p><b>为什么排序键还要补主键</b>：排序键可能不唯一（金额相同的两行）。主键不参与业务排序，
 * 只在并列时给一个确定顺序，游标才能稳定地"接着往下走"。</p>
 *
 * @author Li
 * @since 2026-10-26
 */
final class CursorPager {

    private final SqlExecutionLimits limits;

    CursorPager(SqlExecutionLimits limits) {
        this.limits = limits;
    }

    /**
     * 准备阶段的结果：两段 SQL + 要读/要剥的列。
     *
     * @param firstPageSql    第 1 页 SQL（没有游标谓词）
     * @param nextPageSql     第 2 页起 SQL（带 {@code ?} 占位符）
     * @param cursorLabels    从结果里读哪几列当书签
     * @param injectedLabels  临时注入、返回前要剥掉的列
     * @param sortKeys        实际生效的排序键（含补上的主键）
     */
    record PreparedPages(String firstPageSql, String nextPageSql, List<String> cursorLabels,
                         List<String> injectedLabels, List<GuardedSql.SortKey> sortKeys) {
    }

    /**
     * 翻页结果。
     *
     * @param columns   返回列（已剥掉临时列）
     * @param rows      行
     * @param pages     实际执行页数
     * @param completed 是否取完
     */
    record PagingResult(List<SqlQueryColumn> columns, List<List<Object>> rows, int pages,
                        boolean completed, String truncationReason) {
    }

    /**
     * 逐页取数，直到取完或撞上限。
     *
     * @param connection  只读连接
     * @param guardedSql  守卫产物（必须带主键）
     * @return 翻页结果
     * @throws SQLException 执行失败
     */
    PagingResult fetch(Connection connection, GuardedSql guardedSql) throws SQLException {
        // 1. 准备。调用 prepare() 把原始 SQL 改写成两段 SQL + 列信息
        PreparedPages pages = prepare(guardedSql);
        List<List<Object>> allRows = new ArrayList<>();
        List<SqlQueryColumn> columns = List.of();
        RowLayout layout = null;
        List<Object> cursor = null;
        int executedPages = 0;
        boolean completed = false;
        String truncationReason = null;

        while (true) {
            // 2. 计算本页要取多少行
            int remaining = limits.maxTotalRows() - allRows.size();
            if (remaining <= 0) {
                break;
            }
            int requested = Math.min(limits.pageSize(), remaining);
            // 3. 第 1 页用 firstPageSql，后续页用 nextPageSql
            String sql = executedPages == 0 ? pages.firstPageSql() : pages.nextPageSql();
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(limits.timeoutSeconds());
                statement.setMaxRows(requested + 1);
                if (executedPages > 0) {
                    // 4. 绑定游标
                    bindCursor(statement, cursor, pages.sortKeys());
                }
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (layout == null) {
                        // 5. 第 1 页，初始化布局
                        layout = RowLayout.of(resultSet.getMetaData(), pages);
                        columns = layout.columns();
                    }
                    // 6. 读取一页数据
                    PageBuffer buffer = layout.read(resultSet, requested);
                    executedPages++;
                    if (buffer.fetched() == 0) {
                        completed = true;
                        break;
                    }
                    allRows.addAll(buffer.rows());
                    if (!buffer.hasMore()) {
                        completed = true;
                        break;
                    }
                    // 7. 还有更多，记录最后一行的书签值 cursor
                    cursor = buffer.lastCursor();
                    // 8. 检查是否取完或撞上限
                    if (allRows.size() >= limits.maxTotalRows()) {
                        truncationReason = "ROW_LIMIT";
                        break;
                    }
                    if (executedPages >= limits.maxPages()) {
                        truncationReason = "PAGE_LIMIT";
                        break;
                    }
                }
            }
        }
        return new PagingResult(columns, allRows, executedPages, completed, truncationReason);
    }

    /**
     * 把原始 SQL 改写成"第 1 页 / 第 2 页起"两段。
     *
     * <p>只做翻页必需的三件事：注入游标列、重建 ORDER BY、加游标谓词。
     * 业务条件一个字不动。</p>
     *
     * @param guardedSql 守卫产物
     * @return 两段 SQL 与列信息
     */
    static PreparedPages prepare(GuardedSql guardedSql) {
        // 1. 解析原始 SQL 为 AST
        List<SQLStatement> statements = SQLUtils.parseStatements(guardedSql.sql(), DbType.mysql);
        if (statements.size() != 1 || !(statements.getFirst() instanceof SQLSelectStatement selectStatement)) {
            throw new IllegalArgumentException("翻页只支持单条 SELECT");
        }
        SQLSelectQueryBlock block = selectStatement.getSelect().getQueryBlock();
        if (block == null) {
            throw new IllegalArgumentException("翻页只支持简单查询块");
        }
        // 2. 确定排序键
        List<GuardedSql.SortKey> sortKeys = new ArrayList<>(guardedSql.sortKeys());
        String primaryKey = guardedSql.cursorPrimaryKey();
        boolean hasPrimaryKey = sortKeys.stream()
                .anyMatch(key -> key.column().equalsIgnoreCase(primaryKey));
        if (!hasPrimaryKey) {
            // 3. 不存在主键排序键，兜底使用主键升序
            sortKeys.add(new GuardedSql.SortKey(primaryKey, false));
        }
        // 4. 收集现有 SELECT 列
        Map<String, String> labelByColumn = new LinkedHashMap<>(); // 列名 → 结果标签
        Set<String> usedLabels = new LinkedHashSet<>(); // 已使用的标签（防止注入列名冲突）
        for (SQLSelectItem item : block.getSelectList()) {
            String column = columnName(item.getExpr());
            String label = item.getAlias() != null && !item.getAlias().isBlank()
                    ? resultLabel(item.getAlias()) : column;
            if (label != null) {
                // usedLabels — 已使用的标签（防止注入列名冲突）
                usedLabels.add(label.toLowerCase(Locale.ROOT));
            }
            if (column != null) {
                // labelByColumn — 列名 → 结果标签（模型自己查了的列不重复注入）
                labelByColumn.putIfAbsent(column.toLowerCase(Locale.ROOT), label);
            }
        }
        // 5. 注入游标列
        List<String> cursorLabels = new ArrayList<>(); // 书签列标签
        List<String> injectedLabels = new ArrayList<>(); // 注入的书签列标签
        int injectedIndex = 0;
        for (GuardedSql.SortKey key : sortKeys) {
            String existing = labelByColumn.get(key.column().toLowerCase(Locale.ROOT));
            if (existing != null) {
                // 5.1 存在现有列，直接使用
                cursorLabels.add(existing);
                continue;
            }
            // 5.2 不存在现有列，创建新列标签
            String alias = "__cursor_" + injectedIndex++;
            while (usedLabels.contains(alias.toLowerCase(Locale.ROOT))) {
                alias = "__cursor_" + injectedIndex++ + "_"
                        + Integer.toHexString(ThreadLocalRandom.current().nextInt(16, 0xFFFF));
            }
            usedLabels.add(alias.toLowerCase(Locale.ROOT));
            // 5.3 注入新列
            block.getSelectList().add(new SQLSelectItem(new SQLIdentifierExpr(key.column()), alias));
            cursorLabels.add(alias);
            injectedLabels.add(alias);
        }
        // 6. 重建 ORDER BY：只由排序键组成，保证与游标谓词完全一致
        SQLOrderBy orderBy = new SQLOrderBy();
        for (GuardedSql.SortKey key : sortKeys) {
            orderBy.addItem(new SQLIdentifierExpr(key.column()),
                    key.descending() ? SQLOrderingSpecification.DESC : SQLOrderingSpecification.ASC);
        }
        block.setOrderBy(orderBy);
        // 7. 生成第 1 页 SQL
        String firstPageSql = SQLUtils.toSQLString(selectStatement, DbType.mysql);
        // 8.1 加游标谓词
        SQLExpr predicate = SQLUtils.toSQLExpr(cursorPredicate(sortKeys), DbType.mysql);
        // Druid 渲染时不会按优先级自动补括号：OR 直接拼在 AND 右边会变成
        // "原条件 AND A OR B"，语义就错了。两边都显式标记括号。
        if (predicate instanceof SQLExprImpl expression) {
            // 加括号
            expression.setParenthesized(true);
        }
        // 8.2 原始 WHERE 条件，加括号后拼在 AND 右边
        SQLExpr where = block.getWhere();
        if (where instanceof SQLExprImpl expression) {
            expression.setParenthesized(true);
        }
        // 8.3 如果原始 SQL 没有 WHERE → 直接用游标谓词
        // 如果有 WHERE → 用 AND 连接原始 WHERE 和游标谓词
        block.setWhere(where == null ? predicate
                : new SQLBinaryOpExpr(where, SQLBinaryOperator.BooleanAnd, predicate));
        // 8.4 生成第 2 页 SQL
        String nextPageSql = SQLUtils.toSQLString(selectStatement, DbType.mysql);

        return new PreparedPages(firstPageSql, nextPageSql, List.copyOf(cursorLabels),
                List.copyOf(injectedLabels), List.copyOf(sortKeys));
    }

    /**
     * 游标谓词：按排序键逐层展开。
     *
     * <pre>
     * (k1 与书签比较)
     *   OR (k1 = 书签 AND k2 与书签比较)
     *   OR (k1 = 书签 AND k2 = 书签 AND k3 与书签比较)
     * </pre>
     *
     * <p>{@code DESC} 用 {@code <}，{@code ASC} 用 {@code >}——"排在书签之后"的方向。</p>
     */
    private static String cursorPredicate(List<GuardedSql.SortKey> keys) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                text.append(" OR ");
            }
            text.append('(');
            for (int j = 0; j < i; j++) {
                text.append('`').append(keys.get(j).column()).append("` = ? AND ");
            }
            text.append('`').append(keys.get(i).column()).append('`')
                    .append(keys.get(i).descending() ? " < ?" : " > ?");
            text.append(')');
        }
        return text.toString();
    }

    /**
     * 绑定游标参数，顺序必须与 {@link #cursorPredicate} 的文本顺序一致。
     */
    private static void bindCursor(PreparedStatement statement, List<Object> cursor,
                                   List<GuardedSql.SortKey> keys) throws SQLException {
        int index = 1;
        for (int i = 0; i < keys.size(); i++) {
            for (int j = 0; j < i; j++) {
                statement.setObject(index++, cursor.get(j));
            }
            statement.setObject(index++, cursor.get(i));
        }
    }

    /**
     * 从表达式里取列名；不是普通列时返回 null。
     */
    private static String columnName(SQLExpr expression) {
        if (expression instanceof SQLIdentifierExpr identifier) {
            return normalize(identifier.getName());
        }
        if (expression instanceof SQLPropertyExpr property) {
            return normalize(property.getName());
        }
        return null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace("`", "").replace("\"", "").trim();
    }

    /** 将 SQL 别名还原为 JDBC 返回的列标签，保留转义后的引号字符。 */
    private static String resultLabel(String alias) {
        String label = SQLUtils.normalize(alias, DbType.mysql);
        char quote = alias.charAt(0);
        if (alias.length() > 1 && alias.charAt(alias.length() - 1) == quote
                && (quote == '`' || quote == '\'' || quote == '"')) {
            String delimiter = String.valueOf(quote);
            return label.replace(delimiter + delimiter, delimiter);
        }
        return label;
    }

    /**
     * 一页的读取结果。
     *
     * @param rows       取回的行（最多 requested 行）
     * @param lastCursor 最后一行的书签值
     * @param hasMore    是否还有更多
     * @param fetched    实际从游标里读到的行数（含多读的那一行）
     */
    private record PageBuffer(List<List<Object>> rows, List<Object> lastCursor, boolean hasMore,
                              int fetched) {
    }

    /**
     * 结果集的列布局：哪些列输出、哪些列当书签、每列的类型。
     */
    private record RowLayout(List<SqlQueryColumn> columns, List<Integer> outputIndexes,
                             List<Integer> cursorIndexes, int[] types) {

        static RowLayout of(ResultSetMetaData meta, PreparedPages pages) throws SQLException {
            int count = meta.getColumnCount();
            // 1. 建立标签→索引映射
            Map<String, Integer> indexByLabel = new HashMap<>();
            for (int i = 1; i <= count; i++) {
                indexByLabel.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), i);
            }
            // 2. 收集注入列的索引
            Set<Integer> injected = new HashSet<>();
            for (String label : pages.injectedLabels()) {
                Integer index = indexByLabel.get(label.toLowerCase(Locale.ROOT));
                if (index != null) {
                    injected.add(index);
                }
            }
            List<Integer> outputIndexes = new ArrayList<>(); // 输出的列索引
            List<SqlQueryColumn> columns = new ArrayList<>(); // 输出的列信息
            int[] types = new int[count + 1];
            for (int i = 1; i <= count; i++) {
                types[i] = meta.getColumnType(i); // 先存类型（不管是不是注入列）
                if (injected.contains(i)) {
                    continue; // 注入列不输出
                }
                outputIndexes.add(i);
                columns.add(new SqlQueryColumn(meta.getColumnName(i), meta.getColumnLabel(i),
                        types[i], meta.getColumnTypeName(i),
                        meta.isNullable(i) != ResultSetMetaData.columnNoNulls));
            }
            // 3. 收集游标列的索引
            List<Integer> cursorIndexes = new ArrayList<>();
            for (String label : pages.cursorLabels()) {
                Integer index = indexByLabel.get(label.toLowerCase(Locale.ROOT));
                if (index == null) {
                    throw new IllegalStateException("游标列不在结果里：" + label);
                }
                cursorIndexes.add(index);
            }
            return new RowLayout(List.copyOf(columns), List.copyOf(outputIndexes),
                    List.copyOf(cursorIndexes), types);
        }

        /**
         * 读一页：最多 requested 行，多读一行判断"还有没有"。
         */
        PageBuffer read(ResultSet resultSet, int requested) throws SQLException {
            List<List<Object>> rows = new ArrayList<>();
            List<Object> lastCursor = null;
            int fetched = 0;
            boolean hasMore = false;
            while (resultSet.next()) {
                fetched++;
                if (fetched > requested) {
                    hasMore = true;
                    break;
                }
                Object[] raw = new Object[types.length];
                // 2. 读取所有列（含注入列）
                for (int i = 1; i < types.length; i++) {
                    raw[i] = JdbcValueMapper.read(resultSet, i, types[i]);
                }
                // 3. 提取输出列
                List<Object> output = new ArrayList<>(outputIndexes.size());
                for (int index : outputIndexes) {
                    output.add(raw[index]);
                }
                rows.add(output);
                // 4. 提取游标值
                List<Object> cursor = new ArrayList<>(cursorIndexes.size());
                for (int index : cursorIndexes) {
                    cursor.add(raw[index]);
                }
                lastCursor = cursor;
            }
            return new PageBuffer(rows, lastCursor, hasMore, fetched);
        }
    }
}
