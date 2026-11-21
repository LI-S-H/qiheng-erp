package com.qiheng.erp.ai.skill;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 按目录分组扫描 classpath 下的技能。
 *
 * <p>框架自带的 classpath 注册表对同一个 jar 只能创建一个实例——第二次创建 JAR FileSystem
 * 会失败并静默返回空列表，因此多个智能体分目录加载时必须使用本实现。
 * 正文在扫描时读入并缓存，读取时不再访问文件系统。</p>
 *
 * @author Li
 * @since 2026-11-21
 */
public final class GroupSkillRegistry implements SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(GroupSkillRegistry.class);

    /** 技能名只允许小写字母、数字和单个连字符。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /** 技能分组目录名，如 shared、main、analysis。 */
    private final String group;

    /** 注入到系统提示词中的模板。 */
    private final String promptTemplate;

    /** 已加载技能：名称到元信息。 */
    private volatile Map<String, SkillMetadata> skills = Map.of();

    /**
     * 绑定分组并立即加载。
     *
     * @param group 技能分组目录名
     * @param promptTemplate 系统提示词模板
     */
    public GroupSkillRegistry(String group, String promptTemplate) {
        this.group = group;
        this.promptTemplate = promptTemplate;
        reload();
    }

    /** 重新扫描并替换当前技能集合。 */
    @Override
    public synchronized void reload() {
        Map<String, SkillMetadata> loaded = new LinkedHashMap<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:skills/**/SKILL.md");
            for (Resource resource : resources) {
                if (!matchesGroup(resource)) {
                    continue;
                }
                try {
                    SkillMetadata metadata = read(resource);
                    if (metadata != null) {
                        loaded.putIfAbsent(metadata.getName(), metadata);
                    }
                } catch (Exception error) {
                    log.warn("技能读取失败 group={} resource={}", group, resource.getDescription(), error);
                }
            }
        } catch (IOException error) {
            log.warn("技能目录扫描失败 group={}", group, error);
        }
        this.skills = loaded;
        log.info("技能注册表已加载 group={} count={}", group, loaded.size());
    }

    /**
     * 按名称查找技能。
     *
     * @param name 技能名
     * @return 匹配的技能元信息
     */
    @Override
    public Optional<SkillMetadata> get(String name) {
        return Optional.ofNullable(skills.get(name));
    }

    /**
     * 返回全部技能。
     *
     * @return 技能列表
     */
    @Override
    public List<SkillMetadata> listAll() {
        return List.copyOf(skills.values());
    }

    /**
     * 判断是否包含指定技能。
     *
     * @param name 技能名
     * @return 是否包含
     */
    @Override
    public boolean contains(String name) {
        return skills.containsKey(name);
    }

    /**
     * 返回技能数量。
     *
     * @return 技能数量
     */
    @Override
    public int size() {
        return skills.size();
    }

    /**
     * 读取技能正文。
     *
     * @param name 技能名
     * @return 去掉 frontmatter 的正文
     * @throws IOException 正文缺失
     */
    @Override
    public String readSkillContent(String name) throws IOException {
        SkillMetadata metadata = skills.get(name);
        if (metadata == null) {
            throw new IllegalStateException("技能不存在: " + name);
        }
        return metadata.loadFullContent();
    }

    /**
     * 返回技能加载说明；当前模板不使用该占位符。
     *
     * @return 空串
     */
    @Override
    public String getSkillLoadInstructions() {
        return "";
    }

    /**
     * 返回注册表类型标识。
     *
     * @return 类型标识
     */
    @Override
    public String getRegistryType() {
        return "classpath-group";
    }

    /**
     * 返回系统提示词模板。
     *
     * @return 提示词模板
     */
    @Override
    public SystemPromptTemplate getSystemPromptTemplate() {
        return new SystemPromptTemplate(promptTemplate);
    }

    /** 判断资源是否属于当前分组目录。 */
    private boolean matchesGroup(Resource resource) throws IOException {
        return resource.getURL().toString().contains("/skills/" + group + "/");
    }

    /** 读取一个技能文件；格式不符合要求时返回空。 */
    private SkillMetadata read(Resource resource) throws IOException {
        String content = resource.getContentAsString(StandardCharsets.UTF_8);
        Map<String, Object> frontmatter = parseFrontmatter(content);
        String name = text(frontmatter.get("name"));
        String description = text(frontmatter.get("description"));
        if (name.isEmpty() || description.isEmpty() || !NAME_PATTERN.matcher(name).matches()) {
            log.warn("技能元信息不完整 group={} resource={}", group, resource.getDescription());
            return null;
        }
        return SkillMetadata.builder()
                .name(name)
                .description(description)
                .skillPath("skills/" + group + "/" + name)
                .source("classpath")
                .fullContent(stripFrontmatter(content))
                .build();
    }

    /** 解析 YAML frontmatter；缺失或格式错误时返回空表。 */
    private Map<String, Object> parseFrontmatter(String content) {
        if (!content.startsWith("---")) {
            return Map.of();
        }
        int end = content.indexOf("\n---", 3);
        if (end < 0) {
            return Map.of();
        }
        try {
            Object parsed = new Yaml().load(content.substring(3, end));
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
        } catch (Exception error) {
            log.warn("技能 frontmatter 解析失败 group={}", group, error);
        }
        return Map.of();
    }

    /** 去掉 frontmatter，只保留正文。 */
    private String stripFrontmatter(String content) {
        if (!content.startsWith("---")) {
            return content.strip();
        }
        int end = content.indexOf("\n---", 3);
        if (end < 0) {
            return content.strip();
        }
        return content.substring(end + 4).strip();
    }

    /** 把元信息值转成去空白的字符串。 */
    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}