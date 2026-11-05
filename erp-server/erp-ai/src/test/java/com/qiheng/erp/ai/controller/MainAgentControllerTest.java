package com.qiheng.erp.ai.controller;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.qiheng.erp.ai.clarification.ClarificationService;
import com.qiheng.erp.ai.agent.MainAgentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class MainAgentControllerTest {
    private org.mockito.MockedStatic<com.qiheng.erp.security.context.UserContext> currentUser;

    @org.junit.jupiter.api.BeforeEach
    void setUpUser() {
        var user = new com.qiheng.erp.security.domain.dto.LoginUser();
        user.setUserId(1L);
        currentUser = org.mockito.Mockito.mockStatic(com.qiheng.erp.security.context.UserContext.class);
        currentUser.when(com.qiheng.erp.security.context.UserContext::requireCurrentUser).thenReturn(user);
    }

    @org.junit.jupiter.api.AfterEach
    void clearUser() { currentUser.close(); }

    /** 框架异常结束时主动终止订阅，不能让页面一直等待。 */
    @Test
    void producerWithoutTerminalEventReportsFailure() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ClarificationService> clarifications = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        when(agent.streamForCurrentUser(eq(1L), eq("查询"), anyString())).thenReturn(Flux.empty());
        var events = new MainAgentController(agents, clarifications).stream("1", new MainAgentController.MessageRequest("查询")).collectList().block();
        assertThat(events).extracting(e -> e.event()).containsExactly("run_snapshot", "run_failed");
    }

    @Test
    void fallbackStartsNewSegmentWithoutExposingInternalEvent() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ClarificationService> clarifications = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var fragment = mock(StreamingOutput.class);
        when(fragment.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(fragment.chunk()).thenReturn("说明");
        var fallback = NodeOutput.of("model_tool_fallback", "main_agent", new OverAllState(Map.of()), null);
        var question = NodeOutput.of("ask_user", "main_agent", new OverAllState(Map.of("questionRequestId", "q", "questions", List.of())), null);
        when(agent.streamForCurrentUser(eq(1L), eq("查询"), anyString())).thenReturn(Flux.just(fragment, fallback, question, fragment));
        var events = new MainAgentController(agents, clarifications).stream("1", new MainAgentController.MessageRequest("查询")).filter(e -> !"run_snapshot".equals(e.event()) && !"run_failed".equals(e.event())).collectList().block();
        assertThat(events).extracting(e -> e.event()).containsExactly("text_delta", "question", "text_delta");
        assertThat(((Map<?, ?>) events.get(0).data()).get("messageId")).isNotEqualTo(((Map<?, ?>) events.get(2).data()).get("messageId"));
    }
    /** 工具事件来自另一条回调，先抵达时不能提前切换文字段。 */
    @Test
    void earlyToolEventDoesNotMoveCompletionToNextSegment() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ClarificationService> clarifications = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var fragment = mock(StreamingOutput.class);
        when(fragment.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(fragment.chunk()).thenReturn("先查");
        var tool = NodeOutput.of("tool_started", "main_agent", new OverAllState(Map.of("toolCallId", "call")), null);
        when(agent.streamForCurrentUser(eq(1L), eq("查询"), anyString())).thenReturn(Flux.just(fragment, tool, modelHook("先查库存。"), fragment));
        var events = new MainAgentController(agents, clarifications).stream("1", new MainAgentController.MessageRequest("查询")).filter(e -> !"run_snapshot".equals(e.event()) && !"run_failed".equals(e.event())).collectList().block();
        assertThat(events).extracting(e -> e.event()).containsExactly("text_delta", "tool_started", "text_completed", "text_delta");
        assertThat(((Map<?, ?>) events.get(2).data()).get("messageId"))
                .isEqualTo(((Map<?, ?>) events.get(0).data()).get("messageId"))
                .isNotEqualTo(((Map<?, ?>) events.get(3).data()).get("messageId"));
    }

    @Test
    void orderedAfterModelHookCorrectsToolTextWithoutCheckpoint() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ClarificationService> clarifications = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var fragment = mock(StreamingOutput.class);
        when(fragment.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(fragment.chunk()).thenReturn("先查");
        var call = new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("call", "function", "query_sql_data", "{}");
        var assistant = org.springframework.ai.chat.messages.AssistantMessage.builder().content("先查库存。")
                .toolCalls(List.of(call)).build();
        var hook = NodeOutput.of("_AGENT_HOOK_message_recording.afterModel", "main_agent",
                new OverAllState(Map.of("messages", List.of(assistant))), null);
        var tool = NodeOutput.of("tool_started", "main_agent", new OverAllState(Map.of("toolCallId", "call")), null);
        when(agent.streamForCurrentUser(eq(1L), eq("查询"), anyString())).thenReturn(Flux.just(fragment, hook, tool, fragment));
        var events = new MainAgentController(agents, clarifications).stream("1", new MainAgentController.MessageRequest("查询")).filter(e -> !"run_snapshot".equals(e.event()) && !"run_failed".equals(e.event())).collectList().block();
        assertThat(events).extracting(e -> e.event()).containsExactly("text_delta", "text_completed", "tool_started", "text_delta");
        assertThat(((Map<?, ?>) events.get(1).data()).get("text")).isEqualTo("先查库存。");
        assertThat(((Map<?, ?>) events.get(1).data()).get("messageId"))
                .isEqualTo(((Map<?, ?>) events.get(0).data()).get("messageId"))
                .isNotEqualTo(((Map<?, ?>) events.get(3).data()).get("messageId"));
    }
    @Test
    void emitsOnlyModelFragmentsAndToolStatus() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ClarificationService> clarifications = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var start = NodeOutput.of("run_started", "main_agent", new OverAllState(Map.of("runId", "r", "conversationId", "1")), null);
        var fragment = mock(StreamingOutput.class);
        when(fragment.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(fragment.chunk()).thenReturn("先查询");
        var finished = mock(StreamingOutput.class);
        when(finished.getOutputType()).thenReturn(OutputType.AGENT_MODEL_FINISHED);
        when(finished.chunk()).thenReturn("先查询");
        var tool = NodeOutput.of("tool_started", "main_agent", new OverAllState(Map.of("toolCallId", "t", "tool", "query_sql_data", "label", "正在查询业务数据")), null);
        var end = mock(NodeOutput.class);
        when(end.isEND()).thenReturn(true);
        when(end.state()).thenReturn(new OverAllState(Map.of()));
        when(agent.streamForCurrentUser(eq(1L), eq("查询"), anyString())).thenReturn(Flux.just(start, fragment, finished, tool, end));
        var events = new MainAgentController(agents, clarifications).stream("1", new MainAgentController.MessageRequest("查询"))
                .filter(e -> !"run_snapshot".equals(e.event()) && !"run_failed".equals(e.event())).collectList().block();
        assertThat(events).extracting(e -> e.event()).containsExactly("run_started", "text_delta", "tool_started", "run_completed");
        assertThat(events.get(1).data()).asString().contains("先查询");
        assertThat(((Map<?, ?>) events.get(1).data()).keySet())
                .isEqualTo(java.util.Set.of("messageId", "delta", "eventSeq"));
    }

    @Test
    void completeTextCorrectsTheSameSegmentBeforeToolAndAtEnd() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ClarificationService> clarifications = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var start = NodeOutput.of("run_started", "main_agent", new OverAllState(Map.of("runId", "r")), null);
        var fragment = mock(StreamingOutput.class);
        when(fragment.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(fragment.chunk()).thenReturn("查询");
        var complete = modelHook("查询库存。");
        var tool = NodeOutput.of("tool_started", "main_agent", new OverAllState(Map.of("toolCallId", "t")), null);
        var end = mock(NodeOutput.class);
        when(end.isEND()).thenReturn(true);
        when(end.state()).thenReturn(new OverAllState(Map.of("messages", List.of(new org.springframework.ai.chat.messages.AssistantMessage("结果完整。")))));
        when(agent.streamForCurrentUser(eq(1L), eq("查询"), anyString())).thenReturn(Flux.just(start, fragment, complete, tool, fragment, end));
        var events = new MainAgentController(agents, clarifications).stream("1", new MainAgentController.MessageRequest("查询")).filter(e -> !"run_snapshot".equals(e.event()) && !"run_failed".equals(e.event())).collectList().block();
        assertThat(events).extracting(e -> e.event()).containsExactly("run_started", "text_delta", "text_completed", "tool_started", "text_delta", "text_completed", "run_completed");
        var first = (Map<?, ?>) events.get(1).data();
        var correction = (Map<?, ?>) events.get(2).data();
        var last = (Map<?, ?>) events.get(5).data();
        assertThat(correction.get("messageId")).isEqualTo(first.get("messageId"));
        assertThat(correction.get("text")).isEqualTo("查询库存。");
        assertThat(last.get("messageId")).isEqualTo(((Map<?, ?>) events.get(4).data()).get("messageId"));
        assertThat(last.get("text")).isEqualTo("结果完整。");
    }

    @Test
    void invalidQuestionWithoutToolEventStartsANewTextSegment() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ClarificationService> clarifications = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var start = NodeOutput.of("run_started", "main_agent", new OverAllState(Map.of("runId", "r")), null);
        var fragment = mock(StreamingOutput.class);
        when(fragment.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(fragment.chunk()).thenReturn("说明");
        var boundary = modelHook("说明完整。");
        var emptyBoundary = modelHook("");
        when(agent.streamForCurrentUser(eq(1L), eq("查询"), anyString())).thenReturn(Flux.just(start, fragment, boundary, fragment, emptyBoundary, fragment));
        var events = new MainAgentController(agents, clarifications).stream("1", new MainAgentController.MessageRequest("查询")).filter(e -> !"run_snapshot".equals(e.event()) && !"run_failed".equals(e.event())).collectList().block();
        assertThat(events).extracting(e -> e.event()).containsExactly("run_started", "text_delta", "text_completed", "text_delta", "text_delta");
        var first = (Map<?, ?>) events.get(1).data();
        var corrected = (Map<?, ?>) events.get(2).data();
        var next = (Map<?, ?>) events.get(3).data();
        var afterEmpty = (Map<?, ?>) events.get(4).data();
        assertThat(first.get("messageId")).isEqualTo(corrected.get("messageId")).isNotEqualTo(next.get("messageId"));
        assertThat(next.get("messageId")).isNotEqualTo(afterEmpty.get("messageId"));
    }

    @Test
    void questionSeparatesTextMessages() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ClarificationService> clarifications = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var start = NodeOutput.of("run_started", "main_agent", new OverAllState(Map.of("runId", "r", "conversationId", "1")), null);
        var fragment = mock(StreamingOutput.class);
        when(fragment.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(fragment.chunk()).thenReturn("文字");
        var question = NodeOutput.of("ask_user", "main_agent", new OverAllState(Map.of("questionRequestId", "q", "questions", List.of())), null);
        when(agent.streamForCurrentUser(eq(1L), eq("查询"), anyString())).thenReturn(Flux.just(start, fragment, modelHook("文字"), question, fragment));
        var events = new MainAgentController(agents, clarifications).stream("1", new MainAgentController.MessageRequest("查询"))
                .filter(e -> !"run_snapshot".equals(e.event()) && !"run_failed".equals(e.event())).collectList().block();
        var before = (Map<?, ?>) events.get(1).data();
        var after = (Map<?, ?>) events.get(4).data();
        assertThat(before.get("messageId")).isNotEqualTo(after.get("messageId"));
    }

    private NodeOutput modelHook(String text) {
        var call = new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("call", "function", "query_sql_data", "{}");
        var assistant = org.springframework.ai.chat.messages.AssistantMessage.builder().content(text).toolCalls(List.of(call)).build();
        return NodeOutput.of("_AGENT_HOOK_message_recording.afterModel", "main_agent", new OverAllState(Map.of("messages", List.of(assistant))), null);
    }
}
