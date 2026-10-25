package com.qiheng.erp.ai.service.data.sql.guard;

import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL 守卫的规则契约：每条规则一个用例，坏 SQL 必须被拒、好 SQL 必须放行。
 *
 * <p>守卫是纯函数，不连库；这里的每一条都是"这条规则真的挂在链路上"的证据。</p>
 *
 * @author Li
 * @since 2026-10-25
 */
class DruidSqlGuardTest {

    private static final SqlGuardLimits LIMITS = new SqlGuardLimits(5000);

    private final DruidSqlGuard guard = new DruidSqlGuard();

    // ==================== 拒绝：结构 ====================

    @Test
    void multiStatementIsRejected() {
        assertRejected("SELECT id FROM purchase_order; SELECT id FROM purchase_order",
                SqlGuardRejectCode.MULTI_STATEMENT);
    }

    @Test
    void writeStatementIsRejected() {
        assertRejected("UPDATE purchase_order SET status = 'X'", SqlGuardRejectCode.NOT_SELECT);
        assertRejected("DELETE FROM purchase_order", SqlGuardRejectCode.NOT_SELECT);
        assertRejected("DROP TABLE purchase_order", SqlGuardRejectCode.NOT_SELECT);
    }

    @Test
    void constantQueryIsRejected() {
        assertRejected("SELECT 1", SqlGuardRejectCode.NO_TABLE);
    }

    @Test
    void commentIsRejected() {
        assertRejected("SELECT id FROM purchase_order WHERE deleted = 0 -- 注释",
                SqlGuardRejectCode.COMMENT);
        assertRejected("SELECT id FROM purchase_order WHERE deleted = 0 # 注释",
                SqlGuardRejectCode.COMMENT);
        assertRejected("SELECT id FROM purchase_order /* 注释 */ WHERE deleted = 0",
                SqlGuardRejectCode.COMMENT);
    }

    @Test
    void hashInsideStringIsNotAComment() {
        GuardResult result = guard.check(
                "SELECT purchase_no FROM purchase_order WHERE purchase_no = 'A#1' AND deleted = 0",
                snapshot(), LIMITS, false);

        assertThat(result).isInstanceOf(GuardResult.Ok.class);
    }

    @Test
    void unionIsRejected() {
        assertRejected("SELECT id FROM purchase_order WHERE deleted = 0 "
                        + "UNION SELECT id FROM purchase_order WHERE deleted = 0",
                SqlGuardRejectCode.UNION_NOT_SUPPORTED);
    }

    @Test
    void variableIsRejected() {
        assertRejected("SELECT id FROM purchase_order WHERE id = @x AND deleted = 0",
                SqlGuardRejectCode.VARIABLE_NOT_ALLOWED);
        assertRejected("SELECT id FROM purchase_order WHERE id = @@version AND deleted = 0",
                SqlGuardRejectCode.VARIABLE_NOT_ALLOWED);
    }

    @Test
    void dangerousFunctionIsRejected() {
        assertRejected("SELECT SLEEP(1) FROM purchase_order WHERE deleted = 0",
                SqlGuardRejectCode.DANGEROUS_FUNCTION);
        assertRejected("SELECT id FROM purchase_order WHERE deleted = 0 AND BENCHMARK(1000, MD5('x'))",
                SqlGuardRejectCode.DANGEROUS_FUNCTION);
        assertRejected("SELECT LOAD_FILE('/etc/passwd') FROM purchase_order WHERE deleted = 0",
                SqlGuardRejectCode.DANGEROUS_FUNCTION);
    }

    @Test
    void lockingClauseIsRejected() {
        assertRejected("SELECT id FROM purchase_order WHERE deleted = 0 FOR UPDATE",
                SqlGuardRejectCode.DANGEROUS_CLAUSE);
        assertRejected("SELECT id FROM purchase_order WHERE deleted = 0 LOCK IN SHARE MODE",
                SqlGuardRejectCode.DANGEROUS_CLAUSE);
    }

    @Test
    void selectStarIsRejected() {
        assertRejected("SELECT * FROM purchase_order WHERE deleted = 0",
                SqlGuardRejectCode.SELECT_STAR);
        assertRejected("SELECT t.* FROM purchase_order t WHERE t.deleted = 0",
                SqlGuardRejectCode.SELECT_STAR);
    }

    @Test
    void countStarIsNotSelectStar() {
        GuardResult result = guard.check("SELECT COUNT(*) FROM purchase_order WHERE deleted = 0",
                snapshot(), LIMITS, false);

        assertThat(result).isInstanceOf(GuardResult.Ok.class);
    }

    // ==================== 拒绝：权限 ====================

    @Test
    void systemSchemaIsRejected() {
        assertRejected("SELECT table_name FROM information_schema.tables",
                SqlGuardRejectCode.SYSTEM_SCHEMA);
        assertRejected("SELECT id FROM mysql.user", SqlGuardRejectCode.SYSTEM_SCHEMA);
    }

    @Test
    void otherDatabaseIsRejected() {
        assertRejected("SELECT id FROM otherdb.purchase_order", SqlGuardRejectCode.SYSTEM_SCHEMA);
    }

    @Test
    void tableOutsideScopeIsRejected() {
        assertRejected("SELECT id FROM employee_salary", SqlGuardRejectCode.TABLE_NOT_ALLOWED);
    }

    @Test
    void hiddenColumnIsRejected() {
        // remark 是登记里 status=0 的敏感列，模型看不到，也不该查得到
        assertRejected("SELECT remark FROM purchase_order WHERE deleted = 0",
                SqlGuardRejectCode.COLUMN_NOT_ALLOWED);
        assertRejected("SELECT id FROM purchase_order WHERE deleted = 0 AND cost_price > 10",
                SqlGuardRejectCode.COLUMN_NOT_ALLOWED);
    }

    @Test
    void softDeleteFilterIsRequired() {
        assertRejected("SELECT id FROM purchase_order", SqlGuardRejectCode.SOFT_DELETE_FILTER_MISSING);
        assertRejected("SELECT id FROM purchase_order WHERE status = 'DONE'",
                SqlGuardRejectCode.SOFT_DELETE_FILTER_MISSING);
    }

    @Test
    void softDeleteFilterInsideOrBranchDoesNotCount() {
        // deleted = 0 出现过，但 OR 的另一支能带出已删除行
        assertRejected("SELECT id FROM purchase_order WHERE deleted = 0 OR id = 1",
                SqlGuardRejectCode.SOFT_DELETE_FILTER_MISSING);
        assertRejected("SELECT id FROM purchase_order WHERE status = 'A' OR deleted = 0",
                SqlGuardRejectCode.SOFT_DELETE_FILTER_MISSING);
    }

    @Test
    void softDeleteFilterInEveryOrBranchCounts() {
        assertPassed("SELECT id FROM purchase_order "
                + "WHERE (deleted = 0 AND status = 'A') OR (deleted = 0 AND status = 'B')");
    }

    @Test
    void subqueryFilterDoesNotCoverOuterQuery() {
        // 子查询里过滤了，外层没过滤 —— 外层照样能返回已删除行
        assertRejected("SELECT id FROM purchase_order WHERE id IN "
                        + "(SELECT id FROM purchase_order WHERE deleted = 0)",
                SqlGuardRejectCode.SOFT_DELETE_FILTER_MISSING);
    }

    @Test
    void subqueryAndOuterBothFilteredPasses() {
        assertPassed("SELECT id FROM purchase_order WHERE deleted = 0 AND id IN "
                + "(SELECT id FROM purchase_order WHERE deleted = 0)");
    }

    @Test
    void cteWithoutFilterInsideIsRejected() {
        assertRejected("WITH x AS (SELECT id FROM purchase_order) SELECT id FROM x",
                SqlGuardRejectCode.SOFT_DELETE_FILTER_MISSING);
    }

    @Test
    void selectStarInsideCteIsRejected() {
        assertRejected("WITH x AS (SELECT * FROM purchase_order) SELECT id FROM x",
                SqlGuardRejectCode.SELECT_STAR);
    }

    @Test
    void selectStarInsideSubqueryIsRejected() {
        // 子查询里的星号会把被屏蔽的列带进中间结果
        assertRejected("SELECT t.remark FROM (SELECT * FROM purchase_order) t",
                SqlGuardRejectCode.SELECT_STAR);
    }

    @Test
    void hiddenColumnThroughSubqueryIsRejected() {
        assertRejected("SELECT t.remark FROM (SELECT id, remark FROM purchase_order) t "
                        + "WHERE t.deleted = 0",
                SqlGuardRejectCode.COLUMN_NOT_ALLOWED);
    }

    @Test
    void tableWithoutDeletedColumnDoesNotNeedFilter() {
        GuardResult result = guard.check("SELECT id, stock_qty FROM warehouse_stock",
                snapshot(), LIMITS, false);

        assertThat(result).isInstanceOf(GuardResult.Ok.class);
    }

    // ==================== 拒绝：LIMIT ====================

    @Test
    void limitAboveTotalRowsIsRejected() {
        assertRejected("SELECT id FROM purchase_order WHERE deleted = 0 LIMIT 5001",
                SqlGuardRejectCode.LIMIT_TOO_LARGE);
    }

    @Test
    void fullDataWithLimitIsRejected() {
        assertRejected("SELECT id FROM purchase_order WHERE deleted = 0 LIMIT 100",
                SqlGuardRejectCode.FULL_AND_LIMIT_CONFLICT, true);
    }

    // ==================== 拒绝：取全量的前提 ====================

    @Test
    void fullDataOnJoinIsRejected() {
        assertRejected("SELECT o.id FROM purchase_order o "
                        + "JOIN purchase_order_item i ON i.purchase_order_id = o.id "
                        + "WHERE o.deleted = 0",
                SqlGuardRejectCode.FULL_ON_JOIN_NOT_SUPPORTED, true);
    }

    @Test
    void fullDataOnDistinctIsRejected() {
        assertRejected("SELECT DISTINCT status FROM purchase_order WHERE deleted = 0",
                SqlGuardRejectCode.FULL_ON_DISTINCT_NOT_SUPPORTED, true);
    }

    @Test
    void fullDataOnAggregateIsRejected() {
        assertRejected("SELECT status, COUNT(*) FROM purchase_order WHERE deleted = 0 GROUP BY status",
                SqlGuardRejectCode.FULL_ON_AGGREGATE_NOT_SUPPORTED, true);
    }

    @Test
    void fullDataWithoutPrimaryKeyIsRejected() {
        AuthorizedSchemaSnapshot noPk = AuthorizedSchemaSnapshot.of("t",
                List.of(new AuthorizedSchemaSnapshot.Table("purchase_order", "采购订单", "purchase", true, null)),
                Map.of("purchase_order", List.of(
                        new AuthorizedSchemaSnapshot.Column("id", false, false),
                        new AuthorizedSchemaSnapshot.Column("deleted", false, false))));

        GuardResult result = guard.check("SELECT id FROM purchase_order WHERE deleted = 0", noPk, LIMITS, true);

        assertThat(result).isInstanceOf(GuardResult.Rejected.class);
        assertThat(((GuardResult.Rejected) result).code())
                .isEqualTo(SqlGuardRejectCode.FULL_WITHOUT_PRIMARY_KEY);
    }

    @Test
    void expressionSortKeyIsRejected() {
        assertRejected("SELECT id FROM purchase_order WHERE deleted = 0 ORDER BY total_amount * 2",
                SqlGuardRejectCode.ORDER_BY_EXPRESSION_NOT_ALLOWED, true);
    }

    @Test
    void nullableSortKeyIsRejected() {
        assertRejected("SELECT id FROM purchase_order WHERE deleted = 0 ORDER BY cancelled_at",
                SqlGuardRejectCode.SORT_KEY_NULLABLE, true);
    }

    // ==================== 放行：好 SQL ====================

    @Test
    void plainSelectPasses() {
        GuardResult.Ok ok = assertPassed("SELECT id, purchase_no FROM purchase_order WHERE deleted = 0");

        assertThat(ok.guardedSql().tables()).containsExactly("purchase_order");
        assertThat(ok.guardedSql().limit()).isNull();
        assertThat(ok.guardedSql().sortKeys()).isEmpty();
        assertThat(ok.guardedSql().cursorPrimaryKey()).isNull();
    }

    @Test
    void smallLimitPasses() {
        GuardResult.Ok ok = assertPassed("SELECT id FROM purchase_order WHERE deleted = 0 LIMIT 20");

        assertThat(ok.guardedSql().limit()).isEqualTo(20);
    }

    @Test
    void limitAtCeilingPasses() {
        GuardResult.Ok ok = assertPassed("SELECT id FROM purchase_order WHERE deleted = 0 LIMIT 5000");

        assertThat(ok.guardedSql().limit()).isEqualTo(5000);
    }

    @Test
    void qualifiedColumnsPass() {
        assertPassed("SELECT t.id, t.purchase_no FROM purchase_order t "
                + "WHERE t.deleted = 0 AND t.status = 'DONE'");
    }

    @Test
    void selectAliasCanBeUsedInOrderBy() {
        assertPassed("SELECT purchase_no AS no FROM purchase_order WHERE deleted = 0 ORDER BY no");
    }

    @Test
    void fullDataWithoutOrderByPasses() {
        // 没有 ORDER BY 不算问题：执行器会补主键排序
        GuardResult.Ok ok = assertPassed("SELECT id, purchase_no FROM purchase_order WHERE deleted = 0", true);

        assertThat(ok.guardedSql().sortKeys()).isEmpty();
        assertThat(ok.guardedSql().cursorTable()).isEqualTo("purchase_order");
        assertThat(ok.guardedSql().cursorPrimaryKey()).isEqualTo("id");
    }

    @Test
    void fullDataWithSortKeyKeepsSortKeys() {
        GuardResult.Ok ok = assertPassed("SELECT id, purchase_no, total_amount FROM purchase_order "
                + "WHERE deleted = 0 ORDER BY total_amount DESC", true);

        assertThat(ok.guardedSql().sortKeys())
                .containsExactly(new GuardedSql.SortKey("total_amount", true));
        assertThat(ok.guardedSql().cursorPrimaryKey()).isEqualTo("id");
    }

    @Test
    void fullDataWithAscendingSortKeyKeepsDirection() {
        GuardResult.Ok ok = assertPassed("SELECT id, purchase_no FROM purchase_order "
                + "WHERE deleted = 0 ORDER BY purchase_no ASC", true);

        assertThat(ok.guardedSql().sortKeys())
                .containsExactly(new GuardedSql.SortKey("purchase_no", false));
    }

    @Test
    void joinWithPerTableSoftDeleteFiltersPasses() {
        // JOIN 不做列级检查，但每张有 deleted 列的表都要有自己的过滤
        assertPassed("SELECT o.id, i.quantity FROM purchase_order o "
                + "JOIN purchase_order_item i ON i.purchase_order_id = o.id "
                + "WHERE o.deleted = 0");
    }

    @Test
    void cteAliasIsNotTreatedAsTable() {
        // x 是 CTE 别名，不是真实表；真实表 purchase_order 仍在白名单里
        assertPassed("WITH x AS (SELECT id FROM purchase_order WHERE deleted = 0) "
                + "SELECT id FROM x");
    }

    @Test
    void currentDateIsNotTreatedAsColumn() {
        assertPassed("SELECT id FROM purchase_order WHERE deleted = 0 AND create_time >= CURRENT_DATE");
    }

    // ==================== 工具 ====================

    private GuardResult.Ok assertPassed(String sql) {
        return assertPassed(sql, false);
    }

    private GuardResult.Ok assertPassed(String sql, boolean needFullData) {
        GuardResult result = guard.check(sql, snapshot(), LIMITS, needFullData);
        assertThat(result).as("应放行：%s", sql).isInstanceOf(GuardResult.Ok.class);
        return (GuardResult.Ok) result;
    }

    private void assertRejected(String sql, SqlGuardRejectCode expected) {
        assertRejected(sql, expected, false);
    }

    private void assertRejected(String sql, SqlGuardRejectCode expected, boolean needFullData) {
        GuardResult result = guard.check(sql, snapshot(), LIMITS, needFullData);
        assertThat(result).as("应拒绝：%s", sql).isInstanceOf(GuardResult.Rejected.class);
        GuardResult.Rejected rejected = (GuardResult.Rejected) result;
        assertThat(rejected.code()).as("拒因码：%s", sql).isEqualTo(expected);
        assertThat(rejected.message()).isNotBlank();
    }

    /**
     * 测试用快照：三张表覆盖"有软删除列 / 没有软删除列 / 无主键"几类情况。
     */
    private AuthorizedSchemaSnapshot snapshot() {
        return AuthorizedSchemaSnapshot.of("【DB_ID】 erp", List.of(
                        new AuthorizedSchemaSnapshot.Table("purchase_order", "采购订单", "purchase", true, "id"),
                        new AuthorizedSchemaSnapshot.Table("purchase_order_item", "采购订单明细", "purchase", false, "id"),
                        new AuthorizedSchemaSnapshot.Table("warehouse_stock", "库存", "warehouse", false, "id")),
                Map.of(
                        "purchase_order", List.of(
                                new AuthorizedSchemaSnapshot.Column("id", false, true),
                                new AuthorizedSchemaSnapshot.Column("purchase_no", false, false),
                                new AuthorizedSchemaSnapshot.Column("supplier_id", false, false),
                                new AuthorizedSchemaSnapshot.Column("status", false, false),
                                new AuthorizedSchemaSnapshot.Column("total_amount", false, false),
                                new AuthorizedSchemaSnapshot.Column("create_time", false, false),
                                new AuthorizedSchemaSnapshot.Column("cancelled_at", true, false),
                                new AuthorizedSchemaSnapshot.Column("deleted", false, false)),
                        "purchase_order_item", List.of(
                                new AuthorizedSchemaSnapshot.Column("id", false, true),
                                new AuthorizedSchemaSnapshot.Column("purchase_order_id", false, false),
                                new AuthorizedSchemaSnapshot.Column("quantity", false, false)),
                        "warehouse_stock", List.of(
                                new AuthorizedSchemaSnapshot.Column("id", false, true),
                                new AuthorizedSchemaSnapshot.Column("warehouse_id", false, false),
                                new AuthorizedSchemaSnapshot.Column("stock_qty", false, false))));
    }
}