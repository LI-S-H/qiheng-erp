package com.qiheng.erp.ai.config;

import org.jetbrains.annotations.NotNull;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * AI 取数底座的启用条件。
 *
 * <p>与 {@link AiModelEnabledCondition} 分开判断：模型没配密钥时，ERP 其他模块照常启动，
 * 但取数底座与模型无关——只要 AI 模块开关打开就可以建池，不必等模型可用。</p>
 *
 * <p>AI 模块整体关闭时不建池：连接池一建就会占用数据库连接，
 * 没启用 AI 的环境不该为它留连接。</p>
 *
 * @author Li
 * @since 2026-10-25
 */
public class AiSqlEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, @NotNull AnnotatedTypeMetadata metadata) {
        var environment = context.getEnvironment();
        return environment.getProperty("erp.ai.enabled", Boolean.class, false)
                && environment.getProperty("erp.ai.sql.enabled", Boolean.class, true);
    }
}
