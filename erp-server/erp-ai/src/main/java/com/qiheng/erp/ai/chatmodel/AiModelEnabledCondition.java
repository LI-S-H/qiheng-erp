package com.qiheng.erp.ai.chatmodel;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

/** 未启用或未配置真实密钥时跳过模型创建，ERP 的其他模块仍可正常启动。 */
public class AiModelEnabledCondition implements Condition {
    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var environment = context.getEnvironment();
        String key = environment.getProperty("erp.ai.api-key", "");
        return environment.getProperty("erp.ai.enabled", Boolean.class, false)
                && StringUtils.hasText(key) && !"sk-placeholder".equals(key.trim());
    }
}
