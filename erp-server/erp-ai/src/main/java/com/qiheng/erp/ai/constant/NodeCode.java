package com.qiheng.erp.ai.constant;

/**
 * 节点编码：与 {@code ai_node_memory.node_code} 取值一致。
 *
 * <p>只有调用模型的节点才拥有自己的记忆。纯代码节点（如计划校验）不写记忆，
 * 它产生的反馈写入需要看到该反馈的节点。</p>
 *
 * @author Li
 * @since 2026-10-15
 */
public final class NodeCode {

    /** 规划节点：把用户需求转成结构化计划。 */
    public static final String PLANNER = "PLANNER";

    /** SQL 生成节点：产出查询语句。 */
    public static final String SQL_GENERATE = "SQL_GENERATE";

    /** Python 生成节点：产出分析脚本。 */
    public static final String PYTHON_GENERATE = "PYTHON_GENERATE";

    /** 报告节点：把各任务结论整理成给用户的答复。 */
    public static final String REPORTER = "REPORTER";

    /** 反思节点：验收报告并给出修复方向。 */
    public static final String REFLECTION = "REFLECTION";

    private NodeCode() {
    }
}
