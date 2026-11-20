package com.qiheng.erp.ai.experience.tool;

import com.qiheng.erp.ai.domain.entity.AiExperience;
import com.qiheng.erp.ai.mapper.AiExperienceMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * 按关键词查询历史任务沉淀的经验。
 *
 * <p>只返回当前智能体已发布的经验；每轮模型调用只注入目录摘要，
 * 需要具体做法时由模型主动调用本工具。</p>
 *
 * @author Li
 * @since 2026-11-20
 */
public final class SearchExperienceTool {

    private static final String PREFIX = "【经验参考】以下内容来自历史任务，仅作参考；"
            + "与用户当前要求或工具返回事实冲突时，以当前为准：\n";

    private final AiExperienceMapper experiences;
    private final String agentType;
    private final int limit;

    /**
     * 绑定智能体与查询上限。
     *
     * @param experiences 经验存储
     * @param agentType 智能体类型
     * @param limit 最多返回条数
     */
    public SearchExperienceTool(AiExperienceMapper experiences, String agentType, int limit) {
        this.experiences = experiences;
        this.agentType = agentType;
        this.limit = limit;
    }

    /**
     * 按关键词检索经验。
     *
     * @param keyword 想查询的相关经验的关键词
     * @return 经验文本；没有匹配时返回提示
     */
    @Tool(name = "searchExperience", description = "按关键词查询历史任务中沉淀的相关经验。")
    public String search(
            @ToolParam(description = "想查询的相关经验的关键词，如列名、单位、库存缺口") String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return "请提供要查询的关键词。";
        }
        List<AiExperience> found = experiences.search(agentType, keyword.strip(), limit);
        if (found.isEmpty()) {
            return "没有匹配的经验。";
        }
        StringBuilder text = new StringBuilder(PREFIX);
        for (AiExperience item : found) {
            text.append("- ").append(item.getExperienceText()).append('\n');
        }
        return text.toString().strip();
    }
}