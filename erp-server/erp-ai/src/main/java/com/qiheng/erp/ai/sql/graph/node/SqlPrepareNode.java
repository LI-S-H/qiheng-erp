package com.qiheng.erp.ai.sql.graph.node;

import com.qiheng.erp.ai.sql.graph.SqlNodeSupport;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.update;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.work;

/** 准备当前授权、业务口径与冻结日期。 */
public final class SqlPrepareNode implements NodeAction {
    private final SqlNodeSupport support;

    public SqlPrepareNode(SqlNodeSupport support) {
        this.support = support;
    }

    /** 执行节点并返回 State 的更新。 */
    @Override
    public Map<String, Object> apply(OverAllState state) {
        Map<String, Object> work = work(state); // 复制工作状态，避免修改原状态
        // 刷新权限，失败即 end
        if (!support.refreshAuthorization(work)) {
            return update(work);
    }
        work.put("attempt", 0);
        // 同一次取数的相对日期口径固定；重试和跨午夜恢复不能悄悄换成另一天。
        work.putIfAbsent("currentDate", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
        work.put("timeZone", "Asia/Shanghai");
        work.put("status", "RUNNING");
        work.put("route", "next"); // 清掉上一轮的路由残留
        return update(work);
    }
}
