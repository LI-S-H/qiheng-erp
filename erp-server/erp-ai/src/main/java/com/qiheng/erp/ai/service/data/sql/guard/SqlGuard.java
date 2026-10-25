package com.qiheng.erp.ai.service.data.sql.guard;

import com.qiheng.erp.ai.service.data.sql.guard.model.GuardResult;
import com.qiheng.erp.ai.service.data.sql.guard.model.GuardedSql;
import com.qiheng.erp.ai.service.data.sql.guard.model.SqlGuardLimits;
import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;

/**
 * SQL 守卫：唯一能决定"这条 SQL 能不能执行"的地方。
 *
 * <p>纯函数：不连数据库、不调模型、不碰图。同一输入永远同一结论，可以脱离环境单测。</p>
 *
 * <p><b>两道防线的第一道</b>：守卫负责 ERP 自己的规则（表/列白名单、软删除条件、
 * LIMIT 上限、翻页前提），这些 Druid 的 wall 过滤器不可能知道——它不知道谁能看哪些表。
 * wall 是第二道，兜底拦通用危险形态。</p>
 *
 * @author Li
 * @since 2026-10-25
 */
public interface SqlGuard {

    /**
     * 检查一条由模型生成的 SQL。
     *
     * @param sql          模型生成的 SQL
     * @param snapshot     当前用户的授权 Schema 快照（可见表、可见列、软删除列、主键）
     * @param limits       上限参数
     * @param needFullData 模型是否声明"需要全量明细"（决定是否检查翻页前提）
     * @return 放行（带结构事实）或拒绝（带拒因码与给模型的话）
     */
    GuardResult check(String sql, AuthorizedSchemaSnapshot snapshot, SqlGuardLimits limits,
                      boolean needFullData);
}