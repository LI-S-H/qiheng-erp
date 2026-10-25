package com.qiheng.erp.ai.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 主图的结构参数，对应 {@code erp.ai.graph.*}。
 *
 * <p>这些是代码约束，不是提示词内容：模型看不到上限，也不会因为看到数字而改变拆解粒度。</p>
 *
 * @param maxFormatRetries 首次生成不占额度，之后最多允许重试几次
 * @param maxUserNeeds     同时未完成的用户需求上限
 * @param maxDerivedNeeds  同时未完成的派生需求上限
 * @param maxReviewRounds  业务完整性审阅的回流轮次上限
 * @author Li
 * @since 2026-10-26
 */
@ConfigurationProperties(prefix = "erp.ai.graph")
public record AiGraphProperties(
        @DefaultValue("2") int maxFormatRetries,
        @DefaultValue("3") int maxUserNeeds,
        @DefaultValue("6") int maxDerivedNeeds,
        // 与代码里原来的 :4 保持一致；application-ai.yml 显式配的是 6
        @DefaultValue("4") int maxReviewRounds) {
}