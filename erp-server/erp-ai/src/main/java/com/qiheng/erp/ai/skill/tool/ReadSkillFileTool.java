package com.qiheng.erp.ai.skill.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 读取技能目录下的支持文件。
 *
 * <p>资源直接从 classpath 读取：打包后技能以 jar 内条目形式存在，不能按普通文件打开。
 * 按智能体可见的分组顺序查找，路径必须落在技能自身目录内。</p>
 *
 * @author Li
 * @since 2026-11-21
 */
public final class ReadSkillFileTool {

    /** 技能资源在 classpath 中的前缀。 */
    private static final String CLASSPATH_PREFIX = "skills/";

    /** 技能名只允许小写字母、数字和单个连字符。 */
    private static final Pattern SKILL_NAME = Pattern.compile("^[a-z0-9]+(-[a-z0-9]+)*$");

    /** 支持文件路径只允许安全字符和单层分隔符。 */
    private static final Pattern RELATIVE_PATH = Pattern.compile("^[a-z0-9._-]+(/[a-z0-9._-]+)*$");

    /** 单次读取的文件大小上限。 */
    private static final long MAX_BYTES = 256L * 1024;

    /** 当前智能体可见的技能分组，按优先级排列。 */
    private final List<String> groups;

    /**
     * 绑定技能分组。
     *
     * @param groups 当前智能体可见的技能分组，如 shared、main
     */
    public ReadSkillFileTool(List<String> groups) {
        this.groups = List.copyOf(groups);
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
        for (String group : groups) {
            ClassPathResource resource = new ClassPathResource(
                    CLASSPATH_PREFIX + group + "/" + skill + "/" + path);
            if (!resource.exists()) {
                continue;
            }
            try {
                if (resource.contentLength() > MAX_BYTES) {
                    return "文件超过读取上限，请缩小范围。";
                }
                return resource.getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException error) {
                return "读取失败: " + error.getMessage();
            }
        }
        return "文件不存在: " + path;
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