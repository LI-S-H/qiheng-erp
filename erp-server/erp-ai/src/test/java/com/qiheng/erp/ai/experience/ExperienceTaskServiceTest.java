package com.qiheng.erp.ai.experience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.entity.AiExperience;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper.ExperienceTask;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper.TraceEvent;
import com.qiheng.erp.ai.mapper.AiExperienceMapper;
import com.qiheng.erp.ai.mapper.AiExperienceTaskMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ExperienceTaskServiceTest {
    private final AiExperienceTaskMapper tasks = mock(AiExperienceTaskMapper.class);
    private final AiAgentTraceMapper traces = mock(AiAgentTraceMapper.class);
    private final AiExperienceMapper experiences = mock(AiExperienceMapper.class);
    private final AiMessageMapper messages = mock(AiMessageMapper.class);
    private final ExperienceTaskService service = new ExperienceTaskService(tasks, traces, experiences, messages,
            new ObjectMapper());
    private final ExperienceTask task = new ExperienceTask("MAIN", "run-1", 1L, 2L, 0L);

    @Test
    void terminalRegistrationIsIdempotentAndRecordsNewTerminalOnly() {
        when(traces.selectEvents(1L, 2L, "run-1", "MAIN")).thenReturn(List.of(
                new TraceEvent(1L, "INPUT", "{\"text\":\"问题\"}"),
                new TraceEvent(2L, "FINAL", "{\"text\":\"结果\"}")));
        when(tasks.register(1L, 2L, "MAIN", "run-1")).thenReturn(1, 0);
        service.registerTerminal(1L, 2L, "MAIN", "run-1", "TASK_COMPLETED", "完成");
        service.registerTerminal(1L, 2L, "MAIN", "run-1", "TASK_COMPLETED", "完成");
        verify(traces, times(1)).insert(eq(1L), eq(2L), eq("run-1"), eq("MAIN"),
                eq("TASK_COMPLETED"), any());
    }

    @Test
    void incompleteToolCallDoesNotCreateTask() {
        when(traces.selectEvents(1L, 2L, "run-1", "MAIN")).thenReturn(List.of(
                new TraceEvent(1L, "INPUT", "{\"text\":\"问题\"}"),
                new TraceEvent(2L, "TOOL_CALL", "{\"name\":\"query\",\"callId\":\"c1\"}"),
                new TraceEvent(3L, "FINAL", "{\"text\":\"结果\"}")));
        service.registerTerminal(1L, 2L, "MAIN", "run-1", "TASK_COMPLETED", "完成");
        verify(tasks, never()).register(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void earlierErrorCannotReplaceMissingFinalForSuccessfulTask() {
        when(traces.selectEvents(1L, 2L, "run-1", "MAIN")).thenReturn(List.of(
                new TraceEvent(1L, "INPUT", "{\"text\":\"问题\"}"),
                new TraceEvent(2L, "ERROR", "{\"text\":\"过程错误\"}")));
        service.registerTerminal(1L, 2L, "MAIN", "run-1", "TASK_COMPLETED", "完成");
        verify(tasks, never()).register(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void completionWritesAllExperiencesAndMarksTaskComplete() {
        when(messages.lockOwnedConversation(2L, 1L)).thenReturn(2L);
        when(tasks.finish(1L, 2L, "MAIN", "run-1", "COMPLETED", 0)).thenReturn(1);
        when(experiences.insertPublishedBatch(anyList())).thenReturn(2);
        service.complete(task, List.of("经验甲", "经验乙"));
        @SuppressWarnings("unchecked") var captor = (ArgumentCaptor<List<AiExperience>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
        verify(experiences).insertPublishedBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(2).allSatisfy(item -> {
            assertThat(item.getAgentType()).isEqualTo("MAIN");
            assertThat(item.getSourceTaskId()).isEqualTo("run-1");
            assertThat(item.getStatus()).isEqualTo("PUBLISHED");
        });
    }

    @Test
    void missingConversationDoesNotWriteExperience() {
        when(messages.lockOwnedConversation(2L, 1L)).thenReturn(null);
        when(tasks.finish(1L, 2L, "MAIN", "run-1", "COMPLETED", 0)).thenReturn(1);
        service.complete(task, List.of("经验"));
        verify(experiences, never()).insertPublishedBatch(anyList());
    }

    @Test
    void staleTaskCannotComplete() {
        when(messages.lockOwnedConversation(2L, 1L)).thenReturn(2L);
        when(tasks.finish(1L, 2L, "MAIN", "run-1", "COMPLETED", 0)).thenReturn(0);
        assertThatThrownBy(() -> service.complete(task, List.of("经验"))).isInstanceOf(IllegalStateException.class);
    }
}
