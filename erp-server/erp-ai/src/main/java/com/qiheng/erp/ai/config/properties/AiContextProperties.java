package com.qiheng.erp.ai.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 上下文压缩配置，对应 {@code erp.ai.context.*}。
 *
 * <p>模型窗口是百万级，阈值按窗口的六成设置；保留量按 token 而不是条数，
 * 避免一条超长工具结果把保留段挤爆。摘要输入超出预算时按轮次逐级降级。</p>
 *
 * @param enabled                    是否启用压缩
 * @param maxTokensBeforeSummary     估算 token 达到该值时触发压缩
 * @param keepTokens                 压缩后保留的最近原文 token 数
 * @param minKeepMessages            至少保留的最近消息条数
 * @param keepFirstUserMessage       是否在摘要前保留首条用户消息
 * @param summaryModel               摘要模型：flash 或 pro
 * @param summaryTimeoutSeconds      摘要模型读取超时（秒）
 * @param summaryMaxOutputTokens     单次摘要的最大输出 token 数
 * @param summaryInputMaxTokens      摘要输入的 token 预算，超出时逐级降级
 * @param sourceToolClipChars        降级第一级截断工具结果时前后各保留的字符数
 * @param sourceMessageClipChars     降级第二、三级截断参数或用户文本时前后各保留的字符数
 * @author Li
 * @since 2026-11-20
 */
@ConfigurationProperties(prefix = "erp.ai.context")
public record AiContextProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("650000") int maxTokensBeforeSummary,
        @DefaultValue("60000") int keepTokens,
        @DefaultValue("8") int minKeepMessages,
        @DefaultValue("false") boolean keepFirstUserMessage,
        @DefaultValue("flash") String summaryModel,
        @DefaultValue("180") long summaryTimeoutSeconds,
        @DefaultValue("8000") int summaryMaxOutputTokens,
        @DefaultValue("800000") int summaryInputMaxTokens,
        @DefaultValue("1000") int sourceToolClipChars,
        @DefaultValue("2000") int sourceMessageClipChars) {
}