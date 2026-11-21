package com.qiheng.erp.ai.config;

import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.qiheng.erp.ai.config.properties.AiSkillProperties;
import com.qiheng.erp.ai.skill.CompositeSkillRegistry;
import com.qiheng.erp.ai.skill.GroupSkillRegistry;
import com.qiheng.erp.ai.skill.SkillPrompt;
import com.qiheng.erp.ai.skill.tool.ReadSkillFileTool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.List;

/**
 * 技能注册表装配。
 *
 * <p>技能只从外部目录读取，按 {@code shared}、{@code main}、{@code analysis} 分组；
 * 主智能体使用 shared 与 main，分析智能体使用 shared 与 analysis。
 * 同名技能分属不同分组，互不覆盖。</p>
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
     * @param properties 技能目录配置
     * @return 共享技能注册表
     */
    @Bean
    public GroupSkillRegistry sharedSkillRegistry(AiSkillProperties properties) {
        return registry(properties, "shared");
    }

    /**
     * 主智能体专属技能注册表。
     *
     * @param properties 技能目录配置
     * @return 主智能体技能注册表
     */
    @Bean
    public GroupSkillRegistry mainGroupSkillRegistry(AiSkillProperties properties) {
        return registry(properties, "main");
    }

    /**
     * 分析智能体专属技能注册表。
     *
     * @param properties 技能目录配置
     * @return 分析智能体技能注册表
     */
    @Bean
    public GroupSkillRegistry analysisGroupSkillRegistry(AiSkillProperties properties) {
        return registry(properties, "analysis");
    }

    /**
     * 主智能体技能视图：共享技能加规划类技能。
     *
     * @param shared 共享技能注册表
     * @param main 主智能体专属注册表
     * @return 主智能体技能注册表
     */
    @Bean
    public SkillRegistry mainSkillRegistry(@Qualifier("sharedSkillRegistry") GroupSkillRegistry shared,
                                           @Qualifier("mainGroupSkillRegistry") GroupSkillRegistry main) {
        return new CompositeSkillRegistry(List.of(shared, main));
    }

    /**
     * 分析智能体技能视图：共享技能加执行类技能。
     *
     * @param shared 共享技能注册表
     * @param analysis 分析智能体专属注册表
     * @return 分析智能体技能注册表
     */
    @Bean
    public SkillRegistry analysisSkillRegistry(@Qualifier("sharedSkillRegistry") GroupSkillRegistry shared,
                                               @Qualifier("analysisGroupSkillRegistry") GroupSkillRegistry analysis) {
        return new CompositeSkillRegistry(List.of(shared, analysis));
    }

    /**
     * 主智能体读取技能支持文件的工具。
     *
     * @param shared 共享技能注册表
     * @param main 主智能体专属注册表
     * @return 支持文件读取工具
     */
    @Bean
    public ReadSkillFileTool mainReadSkillFileTool(@Qualifier("sharedSkillRegistry") GroupSkillRegistry shared,
                                                   @Qualifier("mainGroupSkillRegistry") GroupSkillRegistry main) {
        return new ReadSkillFileTool(List.of(shared, main));
    }

    /**
     * 分析智能体读取技能支持文件的工具。
     *
     * @param shared 共享技能注册表
     * @param analysis 分析智能体专属注册表
     * @return 支持文件读取工具
     */
    @Bean
    public ReadSkillFileTool analysisReadSkillFileTool(@Qualifier("sharedSkillRegistry") GroupSkillRegistry shared,
                                                       @Qualifier("analysisGroupSkillRegistry") GroupSkillRegistry analysis) {
        return new ReadSkillFileTool(List.of(shared, analysis));
    }

    /** 按分组创建注册表，目录为技能根目录下的同名子目录。 */
    private GroupSkillRegistry registry(AiSkillProperties properties, String group) {
        Path root = Path.of(properties.dir()).toAbsolutePath().normalize();
        return new GroupSkillRegistry(group, root.resolve(group), SkillPrompt.TEMPLATE);
    }
}