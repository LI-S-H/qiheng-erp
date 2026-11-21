package com.qiheng.erp.ai.experience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.properties.AiExperienceProperties;
import com.qiheng.erp.ai.domain.entity.AiExperience;
import com.qiheng.erp.ai.mapper.AiExperienceMapper;
import com.qiheng.erp.ai.mapper.AiExperienceSummaryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 经验合并的落库契约：按 id 更新、新条目插入、缺失条目软删除，非法 id 丢弃。
 *
 * @author Li
 * @since 2026-11-20
 */
class ExperienceMergeServiceTest {

    @Test
    void updatesInsertsAndSoftDeletesById() throws Exception {
        var experiences = mock(AiExperienceMapper.class);
        var summaries = mock(AiExperienceSummaryMapper.class);
        var model = mock(ChatModel.class);
        when(experiences.selectPublished("MAIN", 100)).thenReturn(List.of(
                item(2L, "【坑】旧的列名经验。", "t2"),
                item(1L, "【做法】旧的步骤经验。", "t1")));
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"summary\":\"SQL 取数注意列名\",\"experiences\":["
                        + "{\"id\":1,\"text\":\"【做法】合并后的步骤经验。\"},"
                        + "{\"id\":null,\"text\":\"【做法】新增的单位经验。\"}]}"));

        service(experiences, summaries, model).mergeAll();

        verify(experiences).updateText(1L, "MAIN", "【做法】合并后的步骤经验。");
        verify(experiences).markDeleted(2L, "MAIN");
        var captor = ArgumentCaptor.forClass(AiExperience.class);
        verify(experiences).insert(captor.capture());
        assertThat(captor.getValue().getSourceTaskId()).isEqualTo("t2");
        verify(summaries).upsert("MAIN", "SQL 取数注意列名");
    }

    @Test
    void invalidIdIsDropped() throws Exception {
        var experiences = mock(AiExperienceMapper.class);
        var summaries = mock(AiExperienceSummaryMapper.class);
        var model = mock(ChatModel.class);
        when(experiences.selectPublished("MAIN", 100)).thenReturn(List.of(
                item(1L, "【做法】现有经验。", "t1")));
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"summary\":\"导航\",\"experiences\":["
                        + "{\"id\":99,\"text\":\"【坑】不存在的 id。\"},"
                        + "{\"id\":1,\"text\":\"【做法】现有经验。\"}]}"));

        service(experiences, summaries, model).mergeAll();

        verify(experiences, never()).updateText(eq(99L), any(), any());
        verify(experiences).updateText(1L, "MAIN", "【做法】现有经验。");
    }

    @Test
    void emptyOutputKeepsExistingData() throws Exception {
        var experiences = mock(AiExperienceMapper.class);
        var summaries = mock(AiExperienceSummaryMapper.class);
        var model = mock(ChatModel.class);
        when(experiences.selectPublished("MAIN", 100)).thenReturn(List.of(
                item(1L, "【做法】现有经验。", "t1")));
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"summary\":\"导航\",\"experiences\":[]}"));

        service(experiences, summaries, model).mergeAll();

        verify(experiences, never()).markDeleted(any(), any());
        verify(summaries, never()).upsert(any(), any());
    }

    @Test
    void summaryIsTruncated() throws Exception {
        var experiences = mock(AiExperienceMapper.class);
        var summaries = mock(AiExperienceSummaryMapper.class);
        var model = mock(ChatModel.class);
        when(experiences.selectPublished("MAIN", 100)).thenReturn(List.of(
                item(1L, "【做法】现有经验。", "t1")));
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"summary\":\"" + "导".repeat(600) + "\",\"experiences\":["
                        + "{\"id\":1,\"text\":\"【做法】现有经验。\"}]}"));

        service(experiences, summaries, model).mergeAll();

        var captor = ArgumentCaptor.forClass(String.class);
        verify(summaries).upsert(eq("MAIN"), captor.capture());
        assertThat(captor.getValue()).hasSize(400);
    }

    private static ExperienceMergeService service(AiExperienceMapper experiences,
                                                  AiExperienceSummaryMapper summaries,
                                                  ChatModel model) throws Exception {
        var properties = new AiExperienceProperties(true, "0 10 3 * * ?", 100, 7, 6,
                600_000, 4000, 5, 400, 100, 400, 10, 60, "flash");
        var client = new ExperienceModelClient(model, model, new ObjectMapper(), properties);
        var provider = new ExperienceProvider(summaries, properties);
        return new ExperienceMergeService(experiences, summaries, client, provider,
                properties, new ObjectMapper());
    }

    private static AiExperience item(Long id, String text, String sourceTaskId) {
        return new AiExperience().setId(id).setAgentType("MAIN").setExperienceText(text)
                .setSourceTaskId(sourceTaskId).setStatus("PUBLISHED").setDeleted(0);
    }

    private static ChatResponse response(String text) {
        var metadata = ChatGenerationMetadata.builder().finishReason("stop").build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text), metadata)));
    }
}