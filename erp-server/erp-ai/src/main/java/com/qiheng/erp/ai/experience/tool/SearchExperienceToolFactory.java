package com.qiheng.erp.ai.experience.tool;

import com.qiheng.erp.ai.config.AiModelEnabledCondition;
import com.qiheng.erp.ai.config.properties.AiExperienceProperties;
import com.qiheng.erp.ai.mapper.AiExperienceMapper;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * 为不同智能体创建独立绑定的经验检索工具。
 *
 * @author Li
 * @since 2026-11-20
 */
@Component
@Conditional(AiModelEnabledCondition.class)
public final class SearchExperienceToolFactory {

    private final AiExperienceMapper experiences;
    private final AiExperienceProperties properties;

    /**
     * 注入经验存储与检索上限。
     *
     * @param experiences 经验存储
     * @param properties 经验配置
     */
    public SearchExperienceToolFactory(AiExperienceMapper experiences, AiExperienceProperties properties) {
        this.experiences = experiences;
        this.properties = properties;
    }

    /**
     * 创建指定智能体的检索工具。
     *
     * @param agentType 智能体类型
     * @return 检索工具
     */
    public SearchExperienceTool create(String agentType) {
        return new SearchExperienceTool(experiences, agentType, properties.searchLimit());
    }
}