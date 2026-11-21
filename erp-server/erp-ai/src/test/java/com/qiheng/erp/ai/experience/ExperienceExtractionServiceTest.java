package com.qiheng.erp.ai.experience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.properties.AiExperienceProperties;
import com.qiheng.erp.ai.domain.entity.AiExperience;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper.ExperienceTask;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper.TraceEvent;
import com.qiheng.erp.ai.mapper.AiExperienceMapper;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 经验提取的落库契约：智能体类型和来源任务由程序填写，密钥脱敏，超长和重复丢弃。
 *
 * @author Li
 * @since 2026-11-20
 */
class ExperienceExtractionServiceTest {

    private static final ExperienceTask TASK = new ExperienceTask("MAIN", "t1", 1L, 2L, 10L);

    @Test
    void writesAgentTypeAndSourceTaskFromTask() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var experiences = mock(AiExperienceMapper.class);
        var model = mock(ChatModel.class);
        when(traces.selectExperienceTasks(any(), anyInt(), anyInt())).thenReturn(List.of(TASK));
        when(traces.selectEvents(1L, 2L, "t1", "MAIN")).thenReturn(events());
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"experiences\":[{\"text\":\"【坑】列名要按实际结果解释。\"}]}"));

        service(traces, experiences, model).extractPending();

        var captor = ArgumentCaptor.forClass(AiExperience.class);
        verify(experiences).insert(captor.capture());
        AiExperience saved = captor.getValue();
        assertThat(saved.getAgentType()).isEqualTo("MAIN");
        assertThat(saved.getSourceTaskId()).isEqualTo("t1");
        assertThat(saved.getStatus()).isEqualTo("PUBLISHED");
    }

    @Test
    void alreadyExtractedTaskIsSkipped() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var experiences = mock(AiExperienceMapper.class);
        var model = mock(ChatModel.class);
        when(traces.selectExperienceTasks(any(), anyInt(), anyInt())).thenReturn(List.of(TASK));
        when(experiences.countBySource("MAIN", "t1")).thenReturn(1);

        service(traces, experiences, model).extractPending();

        verify(traces, never()).selectEvents(anyLong(), anyLong(), any(), any());
        verify(experiences, never()).insert(any(AiExperience.class));
    }

    @Test
    void secretsAreRedacted() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var experiences = mock(AiExperienceMapper.class);
        var model = mock(ChatModel.class);
        when(traces.selectExperienceTasks(any(), anyInt(), anyInt())).thenReturn(List.of(TASK));
        when(traces.selectEvents(1L, 2L, "t1", "MAIN")).thenReturn(events());
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"experiences\":[{\"text\":\"【坑】密钥 sk-abcdefgh123 不应保留。\"}]}"));

        service(traces, experiences, model).extractPending();

        var captor = ArgumentCaptor.forClass(AiExperience.class);
        verify(experiences).insert(captor.capture());
        assertThat(captor.getValue().getExperienceText()).contains("[REDACTED]")
                .doesNotContain("sk-abcdefgh123");
    }

    @Test
    void tooLongTextIsDropped() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var experiences = mock(AiExperienceMapper.class);
        var model = mock(ChatModel.class);
        when(traces.selectExperienceTasks(any(), anyInt(), anyInt())).thenReturn(List.of(TASK));
        when(traces.selectEvents(1L, 2L, "t1", "MAIN")).thenReturn(events());
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"experiences\":[{\"text\":\"" + "内容".repeat(300) + "\"}]}"));

        service(traces, experiences, model).extractPending();

        verify(experiences, never()).insert(any(AiExperience.class));
    }

    @Test
    void duplicatesWithinTaskAreMerged() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var experiences = mock(AiExperienceMapper.class);
        var model = mock(ChatModel.class);
        when(traces.selectExperienceTasks(any(), anyInt(), anyInt())).thenReturn(List.of(TASK));
        when(traces.selectEvents(1L, 2L, "t1", "MAIN")).thenReturn(events());
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"experiences\":[{\"text\":\"【坑】列名要按实际结果解释。\"},"
                        + "{\"text\":\"【坑】列名要按实际结果解释。\"}]}"));

        service(traces, experiences, model).extractPending();

        verify(experiences).insert(any(AiExperience.class));
    }

    private static ExperienceExtractionService service(AiAgentTraceMapper traces,
                                                       AiExperienceMapper experiences,
                                                       ChatModel model) throws Exception {
        var properties = new AiExperienceProperties(true, "0 10 3 * * ?", 100, 7, 1,
                600_000, 4000, 5, 400, 100, 400, 10, 60, "flash");
        var client = new ExperienceModelClient(model, model, new ObjectMapper(), properties);
        return new ExperienceExtractionService(traces, experiences, client, properties, new ObjectMapper());
    }

    private static List<TraceEvent> events() {
        return List.of(
                new TraceEvent(1L, "INPUT", "{\"text\":\"分析 A 仓库存\"}"),
                new TraceEvent(2L, "ERROR", "{\"text\":\"列名不匹配\"}"),
                new TraceEvent(3L, "FINAL", "{\"text\":\"已确认库存不足的商品\"}"));
    }

    private static ChatResponse response(String text) {
        var metadata = ChatGenerationMetadata.builder().finishReason("stop").build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text), metadata)));
    }
}