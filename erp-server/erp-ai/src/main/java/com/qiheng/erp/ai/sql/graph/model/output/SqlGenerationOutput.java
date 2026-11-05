package com.qiheng.erp.ai.sql.graph.model.output;

/** SQL 生成节点的两种业务输出，分别描述可执行查询和需要补充的信息。 */
public sealed interface SqlGenerationOutput permits SqlQueryOutput, SqlNeedInfoOutput {
    String decision();
}
