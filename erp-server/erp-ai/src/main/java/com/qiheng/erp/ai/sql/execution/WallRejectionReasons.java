package com.qiheng.erp.ai.sql.execution;

import java.util.Locale;

/**
 * 把 Druid wall 的英文拒因翻译成模型能直接照做的中文说明。
 *
 * <p>wall 的原始消息形如
 * {@code sql injection violation, dbType mysql, druid-version 1.2.28, comment not allow : SELECT 1 -- x}。
 * 直接把原文丢给模型，它只能看到一串英文和一条自己写的 SQL，不知道要改什么。
 * 这里按"原因段"（冒号之前、最后一个逗号之后的部分）匹配，给出一句可执行的说明。</p>
 *
 * <p><b>只在原因段里匹配</b>，不看后面的 SQL 文本——否则 SQL 里出现
 * {@code updated_at} 这样的列名会被误判成写操作。</p>
 *
 * @author Li
 * @since 2026-10-26
 */
final class WallRejectionReasons {

    private WallRejectionReasons() {
    }

    /**
     * 生成给模型看的原因说明。
     *
     * @param wallMessage wall 过滤器原始消息
     * @return 一句中文说明；无法识别时带上原始原因段
     */
    static String explain(String wallMessage) {
        String reason = reasonOf(wallMessage);
        String lower = reason.toLowerCase(Locale.ROOT);
        if (lower.contains("comment")) {
            return "SQL 里不能写注释（--、#、/* */ 都不行）";
        }
        if (lower.contains("multi-statement")) {
            return "一次只能执行一条语句，请把多条查询合并成一条";
        }
        if (lower.contains("select *") || lower.contains("select all column")) {
            return "不能用 SELECT * 或 t.*，必须显式列出需要的列";
        }
        if (lower.contains("outfile") || lower.contains("dumpfile")) {
            return "不能把查询结果写成服务器文件（INTO OUTFILE / DUMPFILE）";
        }
        if (lower.contains("select into")) {
            return "不能用 SELECT INTO";
        }
        if (lower.contains("update") || lower.contains("insert") || lower.contains("delete")
                || lower.contains("replace") || lower.contains("merge")) {
            return "只能查询，不能写入";
        }
        if (lower.contains("call")) {
            return "不能调用存储过程";
        }
        if (lower.contains("truncate") || lower.contains("create table") || lower.contains("drop table")
                || lower.contains("alter table") || lower.contains("rename table")) {
            return "不能执行 DDL";
        }
        if (lower.contains("where alway true")) {
            return "不能用 WHERE 1=1 这类恒真条件";
        }
        if (lower.contains("set ")) {
            return "不能执行 SET 语句";
        }
        return reason.isBlank() ? "查询形态被安全策略拦截" : "查询被安全策略拦截（" + reason + "）";
    }

    /**
     * 取消息里的"原因段"：冒号之前、最后一个逗号之后。
     *
     * <p>既去掉前面的 {@code sql injection violation, dbType ..., druid-version ...} 前缀，
     * 也去掉后面的 SQL 原文。</p>
     */
    private static String reasonOf(String wallMessage) {
        if (wallMessage == null || wallMessage.isBlank()) {
            return "";
        }
        String reason = wallMessage;
        int colon = reason.indexOf(" : ");
        if (colon > 0) {
            reason = reason.substring(0, colon);
        }
        int lastComma = reason.lastIndexOf(", ");
        if (lastComma > 0) {
            reason = reason.substring(lastComma + 2);
        }
        return reason.trim();
    }
}