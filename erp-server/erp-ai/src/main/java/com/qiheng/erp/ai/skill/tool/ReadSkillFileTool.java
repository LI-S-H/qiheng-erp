package com.qiheng.erp.ai.skill.tool;

import com.alibaba.cloud.ai.graph.skills.SkillMetadata;
import com.qiheng.erp.ai.skill.GroupSkillRegistry;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 读取技能目录下的支持文件。
 *
 * <p>从加载该技能的注册表定位目录，只允许访问该技能自身目录内的 Markdown 文件，
 * 防止模型通过相对路径读取技能目录以外的文件。</p>
 *
 * @author Li
 * @since 2026-11-21
 */
public final class ReadSkillFileTool {

    /** 技能名只允许小写字母、数字和单个连字符。 */
    private static final Pattern SKILL_NAME = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /** 支持文件路径只允许安全字符和单层分隔符。 */
    private static final Pattern RELATIVE_PATH = Pattern.compile("^[a-z0-9._-]+(/[a-z0-9._-]+)*$");

    /** 单次读取的文件大小上限。 */
    private static final long MAX_BYTES = 256L * 1024;

    /** 当前智能体可见的技能注册表，按优先级排列。 */
    private final List<GroupSkillRegistry> registries;

    /**
     * 绑定技能注册表。
     *
     * @param registries 当前智能体可见的技能注册表
     */
    public ReadSkillFileTool(List<GroupSkillRegistry> registries) {
        this.registries = List.copyOf(registries);
    }

    /**
     * 读取技能目录内的支持文件。
     *
     * @param skill 技能名
     * @param path 技能目录内的相对路径，如 references/cases.md
     * @return 文件正文；参数不合法或读取失败时返回可读的原因
     */
    @Tool(name = "readSkillFile",
          description = "读取技能目录下的支持文件，如 references/cases.md。只接受技能目录内的相对路径。")
    public String read(@ToolParam(description = "技能名，如 replenishment") String skill,
            @ToolParam(description = "技能目录内的相对路径，如 references/cases.md") String path) {
        if (skill == null || !SKILL_NAME.matcher(skill).matches()) {
            return "技能名不合法，只允许小写字母、数字和连字符。";
        }
        if (path == null || path.isBlank() || !RELATIVE_PATH.matcher(path).matches()
                || !path.endsWith(".md") || hasParentSegment(path)) {
            return "路径不合法，只接受技能目录内的 Markdown 相对路径。";
        }
        for (GroupSkillRegistry registry : registries) {
            Optional<SkillMetadata> metadata = registry.get(skill);
            if (metadata.isEmpty()) {
                continue;
            }
            Path base = Path.of(metadata.get().getSkillPath()).toAbsolutePath().normalize();
            Path target = base.resolve(path).normalize();
            if (!target.startsWith(base)) {
                return "路径不合法，只接受技能目录内的相对路径。";
            }
            if (!Files.isRegularFile(target)) {
                return "文件不存在: " + path;
            }
            try {
                if (Files.size(target) > MAX_BYTES) {
                    return "文件超过读取上限，请缩小范围。";
                }
                return Files.readString(target, StandardCharsets.UTF_8);
            } catch (IOException error) {
                return "读取失败: " + error.getMessage();
            }
        }
        return "技能不存在: " + skill;
    }

    /** 路径中不允许出现指向上级或当前目录的段。 */
    private boolean hasParentSegment(String path) {
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }
}