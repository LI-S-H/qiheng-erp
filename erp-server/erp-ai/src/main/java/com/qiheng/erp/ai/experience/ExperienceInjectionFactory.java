package com.qiheng.erp.ai.experience;

import com.qiheng.erp.ai.config.AiModelEnabledCondition;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * 按智能体创建经验注入 hook；每个智能体需要独立实例。
 *
 * @author Li
 * @since 2026-11-20
 */
@Component
@Conditional(AiModelEnabledCondition.class)
public final class ExperienceInjectionFactory {

    private final ExperienceProvider provider;

    /**
     * 注入目录摘要来源。
     *
     * @param provider 摘要缓存与查询
     */
    public ExperienceInjectionFactory(ExperienceProvider provider) {
        this.provider = provider;
    }

    /**
     * 创建指定智能体的注入 hook。
     *
     * @param agentType 智能体类型
     * @return 注入 hook
     */
    public ExperienceInjectionHook create(String agentType) {
        return new ExperienceInjectionHook(provider, agentType);
    }
}