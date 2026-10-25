package com.qiheng.erp.ai.service.data.sql.execution;

/**
 * 执行错误分类。
 *
 * <p>分类的目的不是"给错误起个名字"，而是决定重试值不值得、以及给模型什么话：
 * 语法错值得改；权限错和超时改了也没用；{@code WALL_REJECTED} 说明守卫漏了，
 * 要报警而不是让模型重试。</p>
 *
 * @author Li
 * @since 2026-10-26
 */
public enum SqlErrorKind {

    /** 超时：改小范围或加过滤条件可能有用。 */
    TIMEOUT("查询超时"),

    /** 语法或字段错误：模型改 SQL 能修。 */
    SYNTAX("SQL 语法或字段有误"),

    /** 权限不足：改 SQL 也修不了。 */
    PERMISSION("当前账号没有访问这张表的权限"),

    /** 连接异常：与 SQL 无关。 */
    CONNECTION("数据库连接异常"),

    /** 被 wall 过滤器拦下：守卫没拦住，说明规则有缺口。 */
    WALL_REJECTED("查询触发了安全拦截"),

    /** 其它。 */
    UNKNOWN("查询执行失败");

    private final String description;

    SqlErrorKind(String description) {
        this.description = description;
    }

    /**
     * 给用户看的说明（脱敏、不含库地址与堆栈）。
     *
     * @return 说明
     */
    public String userMessage() {
        return description;
    }
}