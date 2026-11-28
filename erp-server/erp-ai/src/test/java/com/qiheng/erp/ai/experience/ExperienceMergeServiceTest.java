package com.qiheng.erp.ai.experience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.properties.AiExperienceProperties;
import com.qiheng.erp.ai.agent.context.TokenEstimator;
import com.qiheng.erp.ai.domain.entity.AiExperience;
import com.qiheng.erp.ai.mapper.AiExperienceMapper;
import com.qiheng.erp.ai.mapper.AiExperienceSummaryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.core.io.ClassPathResource;
import java.nio.charset.StandardCharsets;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 合并必须完整处理分页，异常时不得变更已有经验。 */
class ExperienceMergeServiceTest {

    @Test
    void replacesOnlyAfterReadingAllPages() throws Exception {
        var experiences = mock(AiExperienceMapper.class);
        var summaries = mock(AiExperienceSummaryMapper.class);
        var model = mock(ChatModel.class);
        when(experiences.selectPublishedPage(eq("MAIN"), isNull(), anyInt()))
                .thenReturn(List.of(item(1L, "【做法】第一条经验。")));
        when(experiences.selectPublishedPage(eq("MAIN"), eq(1L), anyInt()))
                .thenReturn(List.of(item(2L, "【做法】第二条经验。")));
        when(experiences.selectPublishedPage(eq("MAIN"), eq(2L), anyInt())).thenReturn(List.of());
        when(experiences.selectPublishedPage(eq("ANALYSIS"), isNull(), anyInt())).thenReturn(List.of());
        when(experiences.selectPublishedForUpdatePage(eq("MAIN"), isNull(), anyInt())).thenReturn(List.of(
                item(1L, "【做法】第一条经验。"), item(2L, "【做法】第二条经验。")));
        when(experiences.deletePublishedByIds(eq("MAIN"), anyList())).thenReturn(2);
        when(experiences.insertPublishedBatch(anyList())).thenReturn(1);
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"summary\":\"目录\",\"experiences\":[{\"text\":\"【做法】合并经验。\"}]}"));

        service(experiences, summaries, model).mergeAll();

        verify(experiences).deletePublishedByIds("MAIN", List.of(1L, 2L));
        verify(summaries).upsert("MAIN", "目录");
    }

    @Test
    void invalidOutputKeepsEverything() throws Exception {
        var experiences = mock(AiExperienceMapper.class);
        var summaries = mock(AiExperienceSummaryMapper.class);
        var model = mock(ChatModel.class);
        when(experiences.selectPublishedPage(eq("MAIN"), isNull(), anyInt()))
                .thenReturn(List.of(item(1L, "【做法】原经验。")));
        when(experiences.selectPublishedPage(eq("MAIN"), eq(1L), anyInt())).thenReturn(List.of());
        when(experiences.selectPublishedPage(eq("ANALYSIS"), isNull(), anyInt())).thenReturn(List.of());
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"summary\":\"目录\",\"experiences\":[]}"));

        service(experiences, summaries, model).mergeAll();

        verify(experiences, never()).deletePublishedByIds(any(), anyList());
        verify(summaries, never()).upsert(any(), any());
    }

    @Test
    void changingSnapshotDuringModelCallKeepsExistingRows() throws Exception {
        var experiences = mock(AiExperienceMapper.class);
        var summaries = mock(AiExperienceSummaryMapper.class);
        var model = mock(ChatModel.class);
        when(experiences.selectPublishedPage(eq("MAIN"), isNull(), anyInt()))
                .thenReturn(List.of(item(1L, "【做法】原经验。")));
        when(experiences.selectPublishedPage(eq("MAIN"), eq(1L), anyInt())).thenReturn(List.of());
        when(experiences.selectPublishedPage(eq("ANALYSIS"), isNull(), anyInt())).thenReturn(List.of());
        when(experiences.selectPublishedForUpdatePage(eq("MAIN"), isNull(), anyInt()))
                .thenReturn(List.of(item(1L, "【做法】并发修改。")));
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"summary\":\"目录\",\"experiences\":[{\"text\":\"【做法】整理后。\"}]}"));

        service(experiences, summaries, model).mergeAll();

        verify(experiences, never()).deletePublishedByIds(any(), anyList());
        verify(summaries, never()).upsert(any(), any());
    }

    @Test
    void oversizedInputIsMergedInStagesWithoutLosingRemainingEntries() throws Exception {
        var experiences = mock(AiExperienceMapper.class);
        var summaries = mock(AiExperienceSummaryMapper.class);
        var model = mock(ChatModel.class);
        String first = "甲".repeat(180);
        String second = "乙".repeat(180);
        when(experiences.selectPublishedPage(eq("MAIN"), isNull(), anyInt()))
                .thenReturn(List.of(item(1L, first), item(2L, second)));
        when(experiences.selectPublishedPage(eq("MAIN"), eq(2L), anyInt())).thenReturn(List.of());
        when(experiences.selectPublishedPage(eq("ANALYSIS"), isNull(), anyInt())).thenReturn(List.of());
        when(experiences.selectPublishedForUpdatePage(eq("MAIN"), isNull(), anyInt())).thenReturn(List.of(item(1L, first), item(2L, second)));
        when(experiences.deletePublishedByIds(eq("MAIN"), anyList())).thenReturn(2);
        when(experiences.insertPublishedBatch(anyList())).thenReturn(1);
        when(model.call(any(Prompt.class))).thenReturn(
                response("{\"summary\":\"阶段\",\"experiences\":[{\"text\":\"第一段结果\"}]}"),
                response("{\"summary\":\"目录\",\"experiences\":[{\"text\":\"两段最终结果\"}]}"));
        String prompt = new ClassPathResource("prompts/experience-merge.md").getContentAsString(StandardCharsets.UTF_8);

        service(experiences, summaries, model, TokenEstimator.estimate(prompt) + 270).mergeAll();

        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(1).getInstructions().toString()).contains("第一段结果", second);
        verify(experiences).deletePublishedByIds("MAIN", List.of(1L, 2L));
    }

    @Test
    void laterStageFailureNeverDeletesEarlierInputs() throws Exception {
        var experiences = mock(AiExperienceMapper.class);
        var summaries = mock(AiExperienceSummaryMapper.class);
        var model = mock(ChatModel.class);
        when(experiences.selectPublishedPage(eq("MAIN"), isNull(), anyInt()))
                .thenReturn(List.of(item(1L, "甲".repeat(180)), item(2L, "乙".repeat(180))));
        when(experiences.selectPublishedPage(eq("MAIN"), eq(2L), anyInt())).thenReturn(List.of());
        when(experiences.selectPublishedPage(eq("ANALYSIS"), isNull(), anyInt())).thenReturn(List.of());
        when(model.call(any(Prompt.class))).thenReturn(
                response("{\"summary\":\"阶段\",\"experiences\":[{\"text\":\"第一段结果\"}]}"),
                response("{\"summary\":\"目录\",\"experiences\":[]}"));
        String prompt = new ClassPathResource("prompts/experience-merge.md").getContentAsString(StandardCharsets.UTF_8);

        service(experiences, summaries, model, TokenEstimator.estimate(prompt) + 270).mergeAll();

        verify(model, times(2)).call(any(Prompt.class));
        verify(experiences, never()).deletePublishedByIds(any(), anyList());
        verify(summaries, never()).upsert(any(), any());
    }

    private static ExperienceMergeService service(AiExperienceMapper experiences,
                                                   AiExperienceSummaryMapper summaries,
                                                   ChatModel model) throws Exception {
        return service(experiences, summaries, model, 80000);
    }

    private static ExperienceMergeService service(AiExperienceMapper experiences,
                                                   AiExperienceSummaryMapper summaries,
                                                   ChatModel model, int inputBudget) throws Exception {
        var properties = new AiExperienceProperties(true, "0 10 3 * * ?", 100, 7, 6,
                600_000, 4000, 5, 400, 100, 400, 10, 60, "flash", inputBudget, 35000, 100, 2, 1000, 3);
        var client = new ExperienceModelClient(model, model, new ObjectMapper(), properties);
        var provider = new ExperienceProvider(summaries, properties);
        var tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        return new ExperienceMergeService(experiences, summaries, client, provider,
                properties, new ObjectMapper(), tx);
    }

    private static AiExperience item(Long id, String text) {
        return new AiExperience().setId(id).setAgentType("MAIN").setExperienceText(text)
                .setStatus("PUBLISHED").setDeleted(0);
    }

    private static ChatResponse response(String text) {
        var metadata = ChatGenerationMetadata.builder().finishReason("stop").build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text), metadata)));
    }
}
