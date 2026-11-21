package com.qiheng.erp.ai.config;

import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.qiheng.erp.ai.skill.CompositeSkillRegistry;
import com.qiheng.erp.ai.skill.GroupSkillRegistry;
import com.qiheng.erp.ai.skill.SkillPrompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 技能注册表装配。
 *
 * <p>共享技能与各智能体专属技能分目录存放，运行时按智能体组合成独立注册表；
 * 主智能体和分析智能体对同一业务问题可以使用同名技能，互不覆盖。</p>
 *
 * @author Li
 * @since 2026-11-21
 */
@Configuration(proxyBeanMethods = false)
@Conditional(AiModelEnabledCondition.class)
public class SkillConfiguration {

    /**
     * 共享技能注册表，供所有智能体复用。
     *
     * @return 共享技能注册表
     */
    @Bean
    public SkillRegistry sharedSkillRegistry() {
        return classpathRegistry("shared");
    }

    /**
     * 主智能体技能注册表：共享技能加规划类技能。
     *
     * @param shared 共享技能注册表
     * @return 主智能体技能注册表
     */
    @Bean
    public SkillRegistry mainSkillRegistry(@Qualifier("sharedSkillRegistry") SkillRegistry shared) {
        return new CompositeSkillRegistry(List.of(shared, classpathRegistry("main")));
    }

    /**
     * 分析智能体技能注册表：共享技能加执行类技能。
     *
     * @param shared 共享技能注册表
     * @return 分析智能体技能注册表
     */
    @Bean
    public SkillRegistry analysisSkillRegistry(@Qualifier("sharedSkillRegistry") SkillRegistry shared) {
        return new CompositeSkillRegistry(List.of(shared, classpathRegistry("analysis")));
    }

    /** 按 classpath 分组创建注册表。 */
    private SkillRegistry classpathRegistry(String group) {
        return new GroupSkillRegistry(group, SkillPrompt.TEMPLATE);
    }
}