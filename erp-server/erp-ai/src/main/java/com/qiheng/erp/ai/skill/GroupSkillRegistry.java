package com.qiheng.erp.ai.skill;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.SystemPromptTemplate;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 从一个外部目录加载技能。
 *
 * <p>目录结构为 {@code <技能根目录>/<分组>/<技能名>/SKILL.md}，正文在加载时读入内存。
 * 技能只来自文件系统，不打包进 jar；{@link #refreshIfChanged()} 通过文件修改时间和大小
 * 判断目录是否变化，变化时才重新加载。</p>
 *
 * @author Li
 * @since 2026-11-21
 */
public final class GroupSkillRegistry implements SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(GroupSkillRegistry.class);

    private static final String SKILL_FILE = "SKILL.md";

    /** 技能名只允许小写字母、数字和单个连字符。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /** 技能分组目录名，如 shared、main、analysis。 */
    private final String group;

    /** 该分组对应的技能目录，绝对路径。 */
    private final Path directory;

    /** 注入到系统提示词中的模板。 */
    private final String promptTemplate;

    /** 已加载技能：名称到元信息。 */
    private volatile Map<String, SkillMetadata> skills = Map.of();

    /** 上次加载时的文件指纹，用于判断目录是否变化。 */
    private volatile String fingerprint = "";

    /**
     * 绑定分组和目录并立即加载。
     *
     * @param group 技能分组目录名
     * @param directory 该分组对应的技能目录
     * @param promptTemplate 系统提示词模板
     */
    public GroupSkillRegistry(String group, Path directory, String promptTemplate) {
        this.group = group;
        this.directory = directory.toAbsolutePath().normalize();
        this.promptTemplate = promptTemplate;
        reload();
    }

    /** 重新扫描目录并替换当前技能集合。 */
    @Override
    public synchronized void reload() {
        Map<String, SkillMetadata> loaded = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) {
            log.warn("技能目录不存在 group={} dir={}", group, directory);
            this.skills = loaded;
            this.fingerprint = "";
            return;
        }
        try (Stream<Path> children = Files.list(directory)) {
            children.filter(Files::isDirectory).sorted().forEach(skillDir -> loadInto(loaded, skillDir));
        } catch (IOException error) {
            log.warn("技能目录读取失败 group={} dir={}", group, directory, error);
        }
        this.skills = loaded;
        this.fingerprint = computeFingerprint();
        log.info("技能注册表已加载 group={} count={} dir={}", group, loaded.size(), directory);
    }

    /**
     * 目录内容变化时重新加载。
     *
     * @return 是否发生了重新加载
     */
    public synchronized boolean refreshIfChanged() {
        String current = computeFingerprint();
        if (current.equals(fingerprint)) {
            return false;
        }
        log.info("检测到技能变化 group={}，重新加载", group);
        reload();
        return true;
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
        return "filesystem-group";
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

    /** 读取一个技能目录；格式不符合要求时跳过。 */
    private void loadInto(Map<String, SkillMetadata> loaded, Path skillDir) {
        Path file = skillDir.resolve(SKILL_FILE);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> frontmatter = parseFrontmatter(content);
            String name = text(frontmatter.get("name"));
            String description = text(frontmatter.get("description"));
            String directoryName = skillDir.getFileName().toString();
            if (name.isEmpty() || description.isEmpty() || !NAME_PATTERN.matcher(name).matches()) {
                log.warn("技能元信息不完整 group={} dir={}", group, skillDir);
                return;
            }
            if (!name.equals(directoryName)) {
                log.warn("技能名与目录名不一致 group={} name={} dir={}", group, name, directoryName);
                return;
            }
            loaded.putIfAbsent(name, SkillMetadata.builder()
                    .name(name)
                    .description(description)
                    .skillPath(skillDir.toString())
                    .source("filesystem")
                    .fullContent(stripFrontmatter(content))
                    .build());
        } catch (IOException error) {
            log.warn("技能读取失败 group={} dir={}", group, skillDir, error);
        }
    }

    /** 计算目录指纹：每个 SKILL.md 的修改时间和大小。 */
    private String computeFingerprint() {
        if (!Files.isDirectory(directory)) {
            return "";
        }
        try (Stream<Path> children = Files.list(directory)) {
            return children.filter(Files::isDirectory)
                    .map(child -> child.resolve(SKILL_FILE))
                    .filter(Files::isRegularFile)
                    .map(this::stamp)
                    .sorted()
                    .collect(Collectors.joining("|"));
        } catch (IOException error) {
            return "";
        }
    }

    /** 单个技能文件的指纹片段。 */
    private String stamp(Path file) {
        try {
            return file.getParent().getFileName() + ":" + Files.getLastModifiedTime(file).toMillis()
                    + ":" + Files.size(file);
        } catch (IOException error) {
            return file.getParent().getFileName() + ":error";
        }
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