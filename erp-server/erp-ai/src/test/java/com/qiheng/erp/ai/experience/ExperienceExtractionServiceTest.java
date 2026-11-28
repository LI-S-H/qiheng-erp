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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    void emptyResultCompletesTaskAndIsNotRetried() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var tasks = mock(ExperienceTaskService.class);
        var model = mock(ChatModel.class);
        when(tasks.pending()).thenReturn(List.of(TASK));
        when(tasks.claim(TASK)).thenReturn(true);
        when(traces.selectEvents(1L, 2L, "t1", "MAIN")).thenReturn(events());
        when(model.call(any(Prompt.class))).thenReturn(response("{\"experiences\":[]}"));

        service(traces, tasks, model).extractPending();

        verify(tasks).complete(TASK, List.of());
        verify(tasks, never()).release(TASK);
    }

    @Test
    void validationFailureIsIncludedInModelInput() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var tasks = mock(ExperienceTaskService.class);
        var model = mock(ChatModel.class);
        when(tasks.pending()).thenReturn(List.of(TASK));
        when(tasks.claim(TASK)).thenReturn(true);
        when(traces.selectEvents(1L, 2L, "t1", "MAIN")).thenReturn(List.of(
                new TraceEvent(1L, "INPUT", "{\"text\":\"问题\"}"),
                new TraceEvent(2L, "VALIDATION_ERROR", "{\"text\":\"参数缺失\"}"),
                new TraceEvent(3L, "FINAL", "{\"text\":\"未完成\"}")));
        when(model.call(any(Prompt.class))).thenReturn(response("{\"experiences\":[]}"));

        service(traces, tasks, model).extractPending();

        var prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        assertThat(prompt.getValue().getInstructions().toString()).contains("VALIDATION_ERROR", "参数缺失");
    }

    @Test
    void sixValidExperiencesAreAllSavedDespitePromptTargetOfFive() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var tasks = mock(ExperienceTaskService.class);
        var model = mock(ChatModel.class);
        when(tasks.pending()).thenReturn(List.of(TASK));
        when(tasks.claim(TASK)).thenReturn(true);
        when(traces.selectEvents(1L, 2L, "t1", "MAIN")).thenReturn(events());
        when(model.call(any(Prompt.class))).thenReturn(response("{\"experiences\":["
                + "{\"text\":\"经验甲\"},{\"text\":\"经验乙\"},{\"text\":\"经验丙\"},"
                + "{\"text\":\"经验丁\"},{\"text\":\"经验戊\"},{\"text\":\"经验己\"}]}"));

        service(traces, tasks, model).extractPending();

        verify(tasks).complete(eq(TASK), eq(List.of("经验甲", "经验乙", "经验丙", "经验丁", "经验戊", "经验己")));
    }

    @Test
    void excessiveOutputIsRetriedTwiceThenRecordedAsError() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var tasks = mock(ExperienceTaskService.class);
        var model = mock(ChatModel.class);
        when(tasks.pending()).thenReturn(List.of(TASK));
        when(tasks.claim(any())).thenReturn(true);
        when(traces.selectEvents(1L, 2L, "t1", "MAIN")).thenReturn(events());
        when(model.call(any(Prompt.class))).thenReturn(response("{\"experiences\":["
                + String.join(",", java.util.Collections.nCopies(11, "{\"text\":\"经验\"}")) + "]}"));

        service(traces, tasks, model).extractPending();

        verify(model, times(3)).call(any(Prompt.class));
        verify(tasks).releaseAfterOverflow(TASK, 1);
        verify(tasks).releaseAfterOverflow(new ExperienceTask("MAIN", "t1", 1L, 2L, 10L, 1), 2);
        verify(tasks).markError(new ExperienceTask("MAIN", "t1", 1L, 2L, 10L, 2), 2);
        verify(tasks, never()).complete(any(), any());
    }

    @Test
    void overflowTaskReturnsToQueueBehindOtherTasks() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var tasks = mock(ExperienceTaskService.class);
        var model = mock(ChatModel.class);
        var second = new ExperienceTask("MAIN", "t2", 1L, 2L, 3L);
        when(tasks.pending()).thenReturn(List.of(TASK, second));
        when(tasks.claim(any())).thenReturn(true);
        when(traces.selectEvents(1L, 2L, "t1", "MAIN")).thenReturn(events());
        when(traces.selectEvents(1L, 2L, "t2", "MAIN")).thenReturn(events());
        String overflow = "{\"experiences\":[" + String.join(",", java.util.Collections.nCopies(11, "{\"text\":\"经验\"}")) + "]}";
        when(model.call(any(Prompt.class))).thenReturn(response(overflow), response("{\"experiences\":[]}"),
                response("{\"experiences\":[]}"));

        service(traces, tasks, model).extractPending();

        var order = org.mockito.Mockito.inOrder(tasks);
        order.verify(tasks).releaseAfterOverflow(TASK, 1);
        order.verify(tasks).complete(second, List.of());
        order.verify(tasks).complete(new ExperienceTask("MAIN", "t1", 1L, 2L, 10L, 1), List.of());
    }

    @Test
    void persistedOverflowRetriesAreNotResetAfterRestart() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var tasks = mock(ExperienceTaskService.class);
        var model = mock(ChatModel.class);
        var retried = new ExperienceTask("MAIN", "t1", 1L, 2L, 0L, 2);
        when(tasks.pending()).thenReturn(List.of(retried));
        when(tasks.claim(retried)).thenReturn(true);
        when(traces.selectEvents(1L, 2L, "t1", "MAIN")).thenReturn(events());
        String overflow = "{\"experiences\":[" + String.join(",", java.util.Collections.nCopies(11, "{\"text\":\"经验\"}")) + "]}";
        when(model.call(any(Prompt.class))).thenReturn(response(overflow));

        service(traces, tasks, model).extractPending();

        verify(model, times(1)).call(any(Prompt.class));
        verify(tasks).markError(retried, 2);
        verify(tasks, never()).releaseAfterOverflow(eq(retried), anyInt());
    }

    @Test
    void transientModelFailureDoesNotMarkPermanentError() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var tasks = mock(ExperienceTaskService.class);
        var model = mock(ChatModel.class);
        when(tasks.pending()).thenReturn(List.of(TASK));
        when(tasks.claim(TASK)).thenReturn(true);
        when(traces.selectEvents(1L, 2L, "t1", "MAIN")).thenReturn(events());
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("临时故障"));

        service(traces, tasks, model).extractPending();

        verify(tasks, never()).markError(eq(TASK), anyInt());
        verify(tasks, times(4)).release(TASK);
    }

    @Test
    void invalidModelOutputUsesAttemptAndBecomesErrorAfterTwoRetries() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var tasks = mock(ExperienceTaskService.class);
        var model = mock(ChatModel.class);
        when(tasks.pending()).thenReturn(List.of(TASK));
        when(tasks.claim(any())).thenReturn(true);
        when(traces.selectEvents(1L, 2L, "t1", "MAIN")).thenReturn(events());
        when(model.call(any(Prompt.class))).thenReturn(response("not json"));

        service(traces, tasks, model).extractPending();

        verify(tasks).releaseAfterOverflow(TASK, 1);
        verify(tasks).releaseAfterOverflow(new ExperienceTask("MAIN", "t1", 1L, 2L, 10L, 1), 2);
        verify(tasks).markError(new ExperienceTask("MAIN", "t1", 1L, 2L, 10L, 2), 2);
    }

    @Test
    void writesAgentTypeAndSourceTaskFromTask() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var tasks = mock(ExperienceTaskService.class);
        var model = mock(ChatModel.class);
        when(tasks.pending()).thenReturn(List.of(TASK));
        when(tasks.claim(TASK)).thenReturn(true);
        when(traces.selectEvents(1L, 2L, "t1", "MAIN")).thenReturn(events());
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"experiences\":[{\"text\":\"【坑】列名要按实际结果解释。\"}]}"));

        service(traces, tasks, model).extractPending();

        verify(tasks).complete(TASK, List.of("【坑】列名要按实际结果解释。"));
    }

    @Test
    void alreadyExtractedTaskIsSkipped() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var tasks = mock(ExperienceTaskService.class);
        var model = mock(ChatModel.class);
        when(tasks.pending()).thenReturn(List.of(TASK));
        when(tasks.claim(TASK)).thenReturn(false);

        service(traces, tasks, model).extractPending();

        verify(traces, never()).selectEvents(anyLong(), anyLong(), any(), any());
        verify(tasks, never()).complete(eq(TASK), any());
    }

    @Test
    void secretsAreRedacted() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var tasks = mock(ExperienceTaskService.class);
        var model = mock(ChatModel.class);
        when(tasks.pending()).thenReturn(List.of(TASK));
        when(tasks.claim(TASK)).thenReturn(true);
        when(traces.selectEvents(1L, 2L, "t1", "MAIN")).thenReturn(events());
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"experiences\":[{\"text\":\"【坑】密钥 sk-abcdefgh123 不应保留。\"}]}"));

        service(traces, tasks, model).extractPending();

        @SuppressWarnings("unchecked")
        var captor = (ArgumentCaptor<List<String>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(tasks).complete(eq(TASK), captor.capture());
        assertThat(captor.getValue().getFirst()).contains("[REDACTED]")
                .doesNotContain("sk-abcdefgh123");
    }

    @Test
    void tooLongTextIsDropped() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var tasks = mock(ExperienceTaskService.class);
        var model = mock(ChatModel.class);
        when(tasks.pending()).thenReturn(List.of(TASK));
        when(tasks.claim(TASK)).thenReturn(true);
        when(traces.selectEvents(1L, 2L, "t1", "MAIN")).thenReturn(events());
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"experiences\":[{\"text\":\"" + "内容".repeat(300) + "\"}]}"));

        service(traces, tasks, model).extractPending();

        verify(tasks).complete(TASK, List.of());
    }

    @Test
    void duplicatesWithinTaskAreMerged() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var tasks = mock(ExperienceTaskService.class);
        var model = mock(ChatModel.class);
        when(tasks.pending()).thenReturn(List.of(TASK));
        when(tasks.claim(TASK)).thenReturn(true);
        when(traces.selectEvents(1L, 2L, "t1", "MAIN")).thenReturn(events());
        when(model.call(any(Prompt.class))).thenReturn(response(
                "{\"experiences\":[{\"text\":\"【坑】列名要按实际结果解释。\"},"
                        + "{\"text\":\"【坑】列名要按实际结果解释。\"}]}"));

        service(traces, tasks, model).extractPending();

        verify(tasks).complete(TASK, List.of("【坑】列名要按实际结果解释。"));
    }

    private static ExperienceExtractionService service(AiAgentTraceMapper traces,
                                                       ExperienceTaskService tasks,
                                                       ChatModel model) throws Exception {
        var properties = new AiExperienceProperties(true, "0 10 3 * * ?", 100, 7, 1,
                600_000, 4000, 5, 400, 100, 400, 10, 60, "flash");
        var client = new ExperienceModelClient(model, model, new ObjectMapper(), properties);
        return new ExperienceExtractionService(traces, tasks, client, properties, new ObjectMapper());
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
