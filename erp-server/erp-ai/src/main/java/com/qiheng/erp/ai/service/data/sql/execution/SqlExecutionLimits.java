package com.qiheng.erp.ai.service.data.sql.execution;

/**
 * 取数执行的上限参数。
 *
 * <p>页大小同时也是"没写 LIMIT 时要不要让模型补声明"的阈值：
 * 返回行数超过它，说明模型没说清自己到底要多少。</p>
 *
 * @param pageSize       每页行数 / 探测阈值
 * @param maxTotalRows   单个证据项允许的最大行数
 * @param maxPages       最多翻几页
 * @param timeoutSeconds 单条查询超时（秒）
 * @author Li
 * @since 2026-10-26
 */
public record SqlExecutionLimits(int pageSize, int maxTotalRows, int maxPages, int timeoutSeconds) {

    public SqlExecutionLimits {
        if (pageSize <= 0 || maxTotalRows <= 0 || maxPages <= 0 || timeoutSeconds <= 0) {
            throw new IllegalArgumentException("取数上限必须是正数");
        }
        if (maxTotalRows < pageSize) {
            throw new IllegalArgumentException("maxTotalRows 不能小于 pageSize");
        }
    }
}