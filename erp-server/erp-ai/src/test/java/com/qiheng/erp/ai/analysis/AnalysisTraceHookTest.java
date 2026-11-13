package com.qiheng.erp.ai.analysis;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;
import com.qiheng.erp.ai.trace.AiAgentTraceRecorder;

/** 验证含文字和多个调用的消息不会丢失任何工具结果。 */
class AnalysisTraceHookTest {
    @Test
    void recordsTextCallsAndEveryToolResponse() {
        var traces = mock(AiAgentTraceMapper.class);
        var recorder = new AiAgentTraceRecorder(traces, new ObjectMapper(), new AiAgentTraceRecorder.Scope(1, 2, "task-1", "ANALYSIS"));
        var hook = new AnalysisTraceHook();
        var calls = List.of(new AssistantMessage.ToolCall("a", "function", "first", "{}"),
                new AssistantMessage.ToolCall("b", "function", "second", "{}"));
        var assistant = AssistantMessage.builder().content("准备数据").toolCalls(calls).build();
        var result = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("a", "first", "one"),
                new ToolResponseMessage.ToolResponse("b", "second", "two"))).build();
        var config = RunnableConfig.builder().threadId("test").addMetadata(AnalysisTraceHook.RECORDER_KEY, recorder).build();

        hook.afterModel(List.of(assistant), config);
        hook.beforeModel(List.of(assistant, result), config);

        verify(traces).insert(eq(1L), eq(2L), eq("task-1"), eq("ANALYSIS"), eq("MODEL_OUTPUT"), contains("准备数据"));
        verify(traces, times(2)).insert(eq(1L), eq(2L), eq("task-1"), eq("ANALYSIS"), eq("TOOL_CALL"), anyString());
        verify(traces, times(2)).insert(eq(1L), eq(2L), eq("task-1"), eq("ANALYSIS"), eq("TOOL_RESULT"), anyString());
    }

    @Test
    void publishesStepWithTextAndToolLabels() {
        var traces = mock(AiAgentTraceMapper.class);
        var recorder = new AiAgentTraceRecorder(traces, new ObjectMapper(), new AiAgentTraceRecorder.Scope(1, 2, "task-1", "ANALYSIS"));
        var events = new java.util.ArrayList<String>();
        var progress = new AnalysisProgress("call-1", (name, data) -> events.add(name + ":" + data.get("text")));
        var hook = new AnalysisTraceHook();
        var assistant = AssistantMessage.builder().content("先看数据")
                .toolCalls(List.of(new AssistantMessage.ToolCall("a", "function", "query_sql_data", "{}"))).build();
        var config = RunnableConfig.builder().threadId("test")
                .addMetadata(AnalysisTraceHook.RECORDER_KEY, recorder)
                .addMetadata(AnalysisProgress.CONTEXT_KEY, progress).build();

        hook.afterModel(List.of(assistant), config);

        assertThat(events).containsExactly("analysis_step:先看数据");
        assertThat(progress.parentToolCallId()).isEqualTo("call-1");
    }

    @Test
    void recorderDoesNotPropagateDatabaseFailure() {
        var traces = mock(AiAgentTraceMapper.class);
        doThrow(new IllegalStateException("数据库不可用")).when(traces)
                .insert(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString());
        var recorder = new AiAgentTraceRecorder(traces, new ObjectMapper(), new AiAgentTraceRecorder.Scope(1, 2, "task-1", "ANALYSIS"));
        recorder.record("INPUT", "任务");
        verify(traces).insert(eq(1L), eq(2L), eq("task-1"), eq("ANALYSIS"), eq("INPUT"), anyString());
    }
}
