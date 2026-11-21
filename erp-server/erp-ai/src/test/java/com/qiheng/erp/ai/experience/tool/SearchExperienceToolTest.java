package com.qiheng.erp.ai.experience.tool;

import com.qiheng.erp.ai.domain.entity.AiExperience;
import com.qiheng.erp.ai.mapper.AiExperienceMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 经验检索工具契约：只返回已发布条目，空关键词和空结果有明确提示。
 *
 * @author Li
 * @since 2026-11-20
 */
class SearchExperienceToolTest {

    @Test
    void returnsMatchingExperiences() {
        var mapper = mock(AiExperienceMapper.class);
        when(mapper.search(eq("MAIN"), eq("列名"), anyInt())).thenReturn(List.of(
                new AiExperience().setExperienceText("【坑】列名按实际结果解释。")));
        var tool = new SearchExperienceTool(mapper, "MAIN", 10);

        String result = tool.search("列名");

        assertThat(result).contains("经验参考", "【坑】列名按实际结果解释。");
    }

    @Test
    void blankKeywordAsksForOne() {
        var tool = new SearchExperienceTool(mock(AiExperienceMapper.class), "MAIN", 10);

        assertThat(tool.search(" ")).contains("请提供要查询的关键词");
    }

    @Test
    void noMatchIsExplicit() {
        var mapper = mock(AiExperienceMapper.class);
        when(mapper.search(eq("ANALYSIS"), eq("库存"), anyInt())).thenReturn(List.of());
        var tool = new SearchExperienceTool(mapper, "ANALYSIS", 10);

        assertThat(tool.search("库存")).contains("没有匹配的经验");
    }

    @Test
    void longKeywordIsSplitIntoTernaryTerms() {
        var mapper = mock(AiExperienceMapper.class);
        when(mapper.search(anyString(), anyString(), anyInt())).thenReturn(List.of());
        when(mapper.search(eq("MAIN"), eq("库存"), anyInt())).thenReturn(List.of(
                new AiExperience().setId(1L).setExperienceText("【口径】库存字段按100倍存储。")));
        var tool = new SearchExperienceTool(mapper, "MAIN", 10);

        assertThat(tool.search("库存分析")).contains("库存字段按100倍存储");
    }
}