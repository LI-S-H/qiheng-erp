package com.qiheng.erp.ai.service.data.sql.guard;

import java.util.List;
import java.util.Set;

/**
 * 守卫放行后的 SQL 及其结构事实。
 *
 * <p><b>只能由守卫产出</b>：执行器只接受这个类型，节点层没有裸 SQL 执行入口。
 * 这样"检查过的 SQL"与"没检查过的 SQL"在类型上就分得开，不会出现某条分支绕过守卫。</p>
 *
 * @param sql              原样透传的 SQL（守卫不改写模型写的语句）
 * @param tables           引用到的真实表（已去库前缀、已排除 CTE 别名）
 * @param limit            模型写的 LIMIT；没有则 null
 * @param sortKeys         模型写的 ORDER BY（列 + 方向）；没有则空列表
 * @param cursorTable      要全量时用于翻页的表（单表）；不需要全量时为 null
 * @param cursorPrimaryKey 该表的主键列；不需要全量时为 null
 * @author Li
 * @since 2026-10-25
 */
public record GuardedSql(
        String sql,
        Set<String> tables,
        Integer limit,
        List<SortKey> sortKeys,
        String cursorTable,
        String cursorPrimaryKey) {

    /**
     * 排序键：一列 + 方向。
     *
     * @param column     列名
     * @param descending 是否降序
     */
    public record SortKey(String column, boolean descending) {
    }
}