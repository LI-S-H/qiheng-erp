package com.qiheng.erp.ai.skill;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 组合多个技能注册表，按优先级查找并合并技能清单。
 *
 * <p>主智能体和分析智能体各自加载共享技能与专属技能；同名技能以先注册者为准，
 * 读取正文时转发给持有该技能的注册表，避免元信息和正文来自不同来源。</p>
 *
 * @author Li
 * @since 2026-11-21
 */
public final class CompositeSkillRegistry implements SkillRegistry {

    /** 按优先级排列的委托注册表。 */
    private final List<SkillRegistry> registries;

    /**
     * 绑定委托注册表。
     *
     * @param registries 按优先级排列的注册表
     */
    public CompositeSkillRegistry(List<SkillRegistry> registries) {
        this.registries = List.copyOf(registries);
    }

    /**
     * 按优先级查找技能。
     *
     * @param name 技能名
     * @return 匹配的技能元信息
     */
    @Override
    public Optional<SkillMetadata> get(String name) {
        for (SkillRegistry registry : registries) {
            Optional<SkillMetadata> found = registry.get(name);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * 合并所有注册表的技能清单，按名称去重。
     *
     * @return 去重后的技能清单
     */
    @Override
    public List<SkillMetadata> listAll() {
        Map<String, SkillMetadata> merged = new LinkedHashMap<>();
        for (SkillRegistry registry : registries) {
            for (SkillMetadata skill : registry.listAll()) {
                merged.putIfAbsent(skill.getName(), skill);
            }
        }
        return List.copyOf(merged.values());
    }

    /**
     * 判断是否包含指定技能。
     *
     * @param name 技能名
     * @return 是否包含
     */
    @Override
    public boolean contains(String name) {
        return get(name).isPresent();
    }

    /**
     * 返回去重后的技能数量。
     *
     * @return 技能数量
     */
    @Override
    public int size() {
        return listAll().size();
    }

    /** 重新加载所有委托注册表。 */
    @Override
    public void reload() {
        registries.forEach(SkillRegistry::reload);
    }

    /**
     * 从持有该技能的注册表读取正文。
     *
     * @param name 技能名
     * @return 去掉 frontmatter 的正文
     * @throws IOException 读取失败
     */
    @Override
    public String readSkillContent(String name) throws IOException {
        for (SkillRegistry registry : registries) {
            if (registry.contains(name)) {
                return registry.readSkillContent(name);
            }
        }
        throw new IllegalStateException("技能不存在: " + name);
    }

    /**
     * 返回技能加载说明，取第一个注册表的实现。
     *
     * @return 加载说明
     */
    @Override
    public String getSkillLoadInstructions() {
        return registries.getFirst().getSkillLoadInstructions();
    }

    /**
     * 返回组合注册表类型标识。
     *
     * @return 类型标识
     */
    @Override
    public String getRegistryType() {
        return "composite";
    }

    /**
     * 返回系统提示词模板，取第一个注册表的实现。
     *
     * @return 提示词模板
     */
    @Override
    public SystemPromptTemplate getSystemPromptTemplate() {
        return registries.getFirst().getSystemPromptTemplate();
    }
}