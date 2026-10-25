package com.qiheng.erp.ai.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 会话工作记忆的压缩参数，对应 {@code erp.ai.memory.*}。
 *
 * <p>阈值决定"模型还能看到多少历史"，是产品参数不是纯技术参数。摘要超时同时被
 * 摘要模型和上下文压缩器使用，集中在这里才能保证两边一致。</p>
 *
 * @param summaryTriggerTokens           触发摘要的 token 阈值
 * @param messagesToKeep                 压缩后保留的最近消息条数
 * @param summaryMaxTokens               摘要本身的最大 token 数
 * @param summaryMaxHistoryRatio         摘要长度占待压缩历史的比例
 * @param summaryProviderMaxOutputTokens 供应商侧输出上限
 * @param summaryTimeoutSeconds          摘要模型读取超时（秒）
 * @author Li
 * @since 2026-10-26
 */
@ConfigurationProperties(prefix = "erp.ai.memory")
public record AiMemoryProperties(
        @DefaultValue("200000") int summaryTriggerTokens,
        @DefaultValue("20") int messagesToKeep,
        @DefaultValue("50000") int summaryMaxTokens,
        @DefaultValue("0.10") double summaryMaxHistoryRatio,
        @DefaultValue("50000") int summaryProviderMaxOutputTokens,
        @DefaultValue("300") long summaryTimeoutSeconds) {
}