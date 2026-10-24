package com.qiheng.erp.ai.config;

import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/**
 * <p>
 * AI 模型启用条件
 * </p>
 *
 * <p>未启用或未配置真实密钥时跳过模型创建，ERP 的其他模块仍可正常启动。</p>
 *
 * @author Li
 * @since 2026-10-08
 */
public class AiModelEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, @NotNull AnnotatedTypeMetadata metadata) {
        var environment = context.getEnvironment();
        String key = environment.getProperty("erp.ai.api-key", "");
        return environment.getProperty("erp.ai.enabled", Boolean.class, false)
                && StringUtils.hasText(key) && !"sk-placeholder".equals(key.trim());
    }
}