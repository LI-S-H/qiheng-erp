package com.qiheng.erp.ai.service.data.sql.guard;

import java.util.Map;

/**
 * SQL 守卫的拒因码与"给模型看的话"。
 *
 * <p><b>这里是规则文案的唯一副本</b>：守卫用它拒绝，SQL 提示词引用同一套码，
 * 不允许在别处再写一份。文案要能直接指导模型改写——模型每次重试只能看到这句话，
 * 说得含糊它就会瞎试（那才是死循环的来源）。</p>
 *
 * <p>模板里的 {@code {name}} 占位符由 {@link #render(Map)} 替换。</p>
 *
 * @author Li
 * @since 2026-10-25
 */
public enum SqlGuardRejectCode {

    PARSE_FAILED("SQL 语法无法解析：请检查是否用了中文标点、漏了引号或括号不完整"),

    MULTI_STATEMENT("一次只能执行一条语句，请把多条查询合并成一条"),

    NOT_SELECT("只能查询，不能写入：只允许 SELECT 或 WITH ... SELECT"),

    NO_TABLE("查询必须引用至少一张授权表，不能是纯常量查询"),

    COMMENT("不要在 SQL 里写注释"),

    UNION_NOT_SUPPORTED("本期不支持 UNION，请改用 IN (...) 或 OR 条件"),

    VARIABLE_NOT_ALLOWED("不能使用变量（用户变量 @x、系统变量 @@x）或占位符 ?，请直接写值"),

    DANGEROUS_FUNCTION("函数 {function} 不允许：SLEEP/BENCHMARK 会拖垮数据库，LOAD_FILE 会读服务器文件，"
            + "GET_LOCK 会占锁，EXTRACTVALUE/UPDATEXML 是报错注入手法"),

    DANGEROUS_CLAUSE("FOR UPDATE / LOCK IN SHARE MODE / SKIP LOCKED 这类加锁子句不允许出现在查询里"),

    SELECT_STAR("必须显式写出需要的列，不能用 SELECT * 或 t.*"),

    SYSTEM_SCHEMA("只能查询本库的表：不允许 information_schema / mysql / performance_schema / sys 或其它库"),

    TABLE_NOT_ALLOWED("表 {table} 不在当前用户的可见范围内；当前可查的表：{tables}"),

    LIMIT_TOO_LARGE("单个证据项最多 {maxRows} 行。你写的 LIMIT {limit} 超出了上限："
            + "这条查询多半该写成聚合（COUNT/SUM/GROUP BY）；如果确实要全部明细，请去掉 LIMIT 并标记「需要全量」"),

    FULL_AND_LIMIT_CONFLICT("已声明「需要全量」就不该再写 LIMIT：请去掉 LIMIT，或改回固定条数并去掉该标记"),

    COLUMN_NOT_ALLOWED("列 {column} 没有开放给当前用户；这张表可用的列：{columns}"),

    SOFT_DELETE_FILTER_MISSING("表 {table} 有软删除列：查询必须带 {table}.deleted = 0"),

    ORDER_BY_EXPRESSION_NOT_ALLOWED("排序键必须是普通列，不能是表达式（例如 a * 2）：请改成按列排序"),

    SORT_KEY_NULLABLE("排序列 {column} 可能为 NULL，游标翻页会漏掉 NULL 行："
            + "请换成非空列，或加上 WHERE {column} IS NOT NULL"),

    FULL_ON_DISTINCT_NOT_SUPPORTED("取全量不支持 DISTINCT：请改用 GROUP BY 去重"),

    FULL_ON_AGGREGATE_NOT_SUPPORTED("聚合结果不会超过 {maxRows} 行；如果真超了，请加分组维度或收窄范围"),

    FULL_ON_JOIN_NOT_SUPPORTED("要取全量明细请只查一张表；多表关联请改成聚合查询，或拆成多步"),

    FULL_WITHOUT_PRIMARY_KEY("表 {table} 没有可用的主键列，无法取全量；请改用固定条数（LIMIT）或聚合查询");

    private final String template;

    SqlGuardRejectCode(String template) {
        this.template = template;
    }

    /**
     * 消息模板：{@code {name}} 为占位符。
     *
     * @return 模板原文
     */
    public String template() {
        return template;
    }

    /**
     * 按参数渲染消息。
     *
     * @param values 占位符名 → 值
     * @return 可直接喂给模型的一句话
     */
    public String render(Map<String, String> values) {
        String message = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            message = message.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return message;
    }
}