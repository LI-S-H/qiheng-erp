package com.qiheng.erp.ai.skill;

/**
 * 技能系统的提示词模板。
 *
 * <p>技能清单只暴露名称和说明，正文由模型按需调用 {@code read_skill} 加载；
 * 支持文件通过 {@code readSkillFile} 按技能目录内的相对路径读取。</p>
 *
 * @author Li
 * @since 2026-11-21
 */
public final class SkillPrompt {

    /** 技能清单与使用说明模板；{skills_list} 由框架替换为可用技能列表。 */
    public static final String TEMPLATE = """
            ## 技能库

            你可以使用以下业务技能。技能只提供判断方法和执行规则，不直接访问数据。

            ### 可用技能

            {skills_list}

            ### 使用方式

            1. 判断当前任务是否匹配某个技能的说明，只加载真正需要的技能，不要一次加载全部。
            2. 匹配时调用 `read_skill`，参数是技能名，返回去掉 frontmatter 的完整规则。
            3. 技能正文提到支持文件时，调用 `readSkillFile`，参数是技能名和技能目录内的相对路径，例如 `references/cases.md`。
            4. 技能规则优先于通用判断；与用户当前要求冲突时以用户要求为准。
            """;

    private SkillPrompt() {
    }
}