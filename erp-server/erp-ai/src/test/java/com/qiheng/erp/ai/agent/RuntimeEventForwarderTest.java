package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.clarification.AskUserTool.Question;
import com.qiheng.erp.ai.clarification.AskUserTool.QuestionType;
import com.qiheng.erp.ai.clarification.ClarificationAnswer;
import com.qiheng.erp.ai.clarification.ClarificationService;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeEventForwarderTest {
    @Test
    void questionEventCarriesItsToolCallId() {
        List<NodeOutput> output = new ArrayList<>();
        var forwarder = new MainAgentService.RuntimeEventForwarder(output::add);
        forwarder.questionCall("call-one");
        var first = forwarder.questionEvent(NodeOutput.of("ask_user", "main_agent",
                new OverAllState(Map.of("questionRequestId", "request-one")), null));
        assertThat(first.state().data()).containsEntry("toolCallId", "call-one")
                .containsEntry("questionRequestId", "request-one");
        forwarder.toolEvent(first);
        forwarder.graphEvent(hook(List.of(call("call-one"))));
        assertThat(output.stream().filter(event -> "ask_user".equals(event.node())).findFirst().orElseThrow()
                .state().data()).containsEntry("toolCallId", "call-one");

        forwarder.questionCall("call-two");
        var second = forwarder.questionEvent(NodeOutput.of("ask_user", "main_agent",
                new OverAllState(Map.of("questionRequestId", "request-two")), null));
        assertThat(second.state().data()).containsEntry("toolCallId", "call-two");
    }
    /** 使用生产默认等待时间验证，事件缺失时追问不会等到用户回答超时。 */
    @Test
    void defaultFallbackReleasesQuestionAfterAboutOneSecond() throws Exception {
        var released = new java.util.concurrent.CountDownLatch(1);
        var forwarder = new MainAgentService.RuntimeEventForwarder(event -> {
            if ("ask_user".equals(event.node())) released.countDown();
        }, "run");
        try {
            forwarder.questionCall("ask");
            long started = System.nanoTime();
            forwarder.toolEvent(NodeOutput.of("ask_user", "main_agent", new OverAllState(Map.of("questionRequestId", "q")), null));
            assertThat(released.await(5, TimeUnit.SECONDS)).isTrue();
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            System.out.println("追问兜底实际等待毫秒数=" + elapsed);
            assertThat(elapsed).isBetween(750L, 4000L);
        } finally {
            forwarder.flush();
        }
    }

    /** 被抑制的错误完成事件不能提前放行另一个工具。 */
    @Test
    void mismatchedHookDoesNotMarkAnotherToolReady() throws Exception {
        List<NodeOutput> output = new java.util.concurrent.CopyOnWriteArrayList<>();
        var released = new java.util.concurrent.CountDownLatch(2);
        var questionReleased = new java.util.concurrent.CountDownLatch(1);
        var forwarder = new MainAgentService.RuntimeEventForwarder(event -> {
            output.add(event);
            if ("ask_user".equals(event.node())) questionReleased.countDown();
            if ("ask_user".equals(event.node()) || "tool_started".equals(event.node())) released.countDown();
        }, "run", 100);
        forwarder.questionCall("a");
        forwarder.toolEvent(NodeOutput.of("ask_user", "main_agent", new OverAllState(Map.of("questionRequestId", "q")), null));
        forwarder.graphEvent(hook(List.of(call("b"))));
        assertThat(questionReleased.await(2, TimeUnit.SECONDS)).isTrue();
        synchronized (forwarder) {
            forwarder.toolEvent(tool("tool_started", "b"));
            assertThat(output).extracting(NodeOutput::node).containsExactly("model_tool_fallback", "ask_user");
        }
        assertThat(released.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(output).extracting(NodeOutput::node).containsExactly("model_tool_fallback", "ask_user", "model_tool_fallback", "tool_started");
        forwarder.flush();
    }

    /** 被取消但已在锁外等待的旧定时任务不能提前释放下一轮事件。 */
    @Test
    void cancelledTimerCannotReleaseNextRound() throws Exception {
        List<NodeOutput> output = new java.util.concurrent.CopyOnWriteArrayList<>();
        var released = new java.util.concurrent.CountDownLatch(1);
        var forwarder = new MainAgentService.RuntimeEventForwarder(event -> {
            output.add(event);
            if ("tool_started".equals(event.node()) && "b".equals(event.state().data().get("toolCallId"))) released.countDown();
        }, "run", 500);
        synchronized (forwarder) {
            forwarder.toolEvent(tool("tool_started", "a"));
            Thread.sleep(600);
            forwarder.graphEvent(hook(List.of(call("a"))));
            forwarder.toolEvent(tool("tool_started", "b"));
        }
        assertThat(released.await(150, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(released.await(2, TimeUnit.SECONDS)).isTrue();
        forwarder.flush();
    }

    /** 模型完成事件缺失时，真实追问仍能展示、接收答案并结束等待。 */
    @Test
    void fallbackQuestionCanBeAnswered() throws Exception {
        var conversations = org.mockito.Mockito.mock(AiConversationMapper.class);
        org.mockito.Mockito.when(conversations.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var users = org.mockito.Mockito.mock(LoginUserService.class);
        var user = new LoginUser();
        user.setUserId(1L);
        user.setPermissionCodes(List.of("ai:assistant:use"));
        org.mockito.Mockito.when(users.findByUserId(1L)).thenReturn(user);
        var service = new ClarificationService(conversations, users, new ObjectMapper(), Duration.ofSeconds(3));
        var displayed = new CompletableFuture<String>();
        var forwarder = new MainAgentService.RuntimeEventForwarder(event -> {
            if ("ask_user".equals(event.node())) displayed.complete((String) event.state().data().get("questionRequestId"));
        }, "run", 25);
        forwarder.questionCall("ask");
        var answer = CompletableFuture.supplyAsync(() -> {
            try {
                var question = new Question("warehouse", "哪个仓库？", QuestionType.TEXT, List.of());
                return service.ask(1, 2, List.of(question), forwarder::toolEvent);
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        try {
            String id = displayed.get(2, TimeUnit.SECONDS);
            try (var context = org.mockito.Mockito.mockStatic(UserContext.class)) {
                context.when(UserContext::requireCurrentUser).thenReturn(user);
                service.answerForCurrentUser(2, id, List.of(new ClarificationAnswer("warehouse", List.of(), "一号仓")), null);
            }
            assertThat(answer.get(2, TimeUnit.SECONDS)).contains("一号仓");
        } finally {
            forwarder.flush();
        }
    }

    /** ID 不匹配的完成事件不能校正当前文字段，也不能阻止追问兜底。 */
    @Test
    void mismatchedHookIsIgnoredBeforeQuestionFallback() throws Exception {
        List<NodeOutput> output = new java.util.concurrent.CopyOnWriteArrayList<>();
        var released = new java.util.concurrent.CountDownLatch(1);
        var forwarder = new MainAgentService.RuntimeEventForwarder(event -> {
            output.add(event);
            if ("ask_user".equals(event.node())) released.countDown();
        }, "run", 25);
        forwarder.questionCall("a");
        forwarder.toolEvent(NodeOutput.of("ask_user", "main_agent", new OverAllState(Map.of("questionRequestId", "q")), null));
        forwarder.graphEvent(hook(List.of(call("b"))));
        assertThat(output).isEmpty();
        assertThat(released.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        forwarder.graphEvent(hook(List.of(call("a"))));
        forwarder.graphEvent(hook(List.of(call("c"))));
        assertThat(output).extracting(NodeOutput::node).containsExactly("model_tool_fallback", "ask_user", "_AGENT_HOOK_message_recording.afterModel");
        forwarder.flush();
    }

    @Test
    void graphEndSeparatesPendingToolBeforeFinalMessage() {
        List<NodeOutput> output = new ArrayList<>();
        var forwarder = new MainAgentService.RuntimeEventForwarder(output::add, "run", 1000);
        forwarder.toolEvent(tool("tool_started", "a"));
        var end = org.mockito.Mockito.mock(NodeOutput.class);
        org.mockito.Mockito.when(end.isEND()).thenReturn(true);
        forwarder.graphEvent(end);
        assertThat(output).hasSize(3);
        assertThat(output.get(0).node()).isEqualTo("model_tool_fallback");
        assertThat(output.get(1).node()).isEqualTo("tool_started");
        assertThat(output.get(2)).isSameAs(end);
    }

    @Test
    void graphEndCancelsPendingQuestionBeforeCompletionCallback() throws Exception {
        List<NodeOutput> output = new java.util.concurrent.CopyOnWriteArrayList<>();
        var end = org.mockito.Mockito.mock(NodeOutput.class);
        org.mockito.Mockito.when(end.isEND()).thenReturn(true);
        var forwarder = new MainAgentService.RuntimeEventForwarder(output::add, "run", 500);
        forwarder.questionCall("ask");
        forwarder.toolEvent(NodeOutput.of("ask_user", "main_agent", new OverAllState(Map.of("questionRequestId", "q")), null));
        forwarder.graphEvent(end);
        Thread.sleep(600);
        assertThat(output).containsExactly(end);
    }

    @Test
    void toolResultReleasesEventsBeforeNextModelAndDropsExpiredQuestion() {
        List<NodeOutput> output = new ArrayList<>();
        var forwarder = new MainAgentService.RuntimeEventForwarder(output::add, "run", 1000);
        forwarder.toolEvent(tool("tool_started", "a"));
        forwarder.toolEvent(tool("tool_finished", "a"));
        forwarder.toolEvent(tool("tool_started", "b"));
        var response = org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(
                new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("a", "query_sql_data", "{}"))).build();
        var toolResult = org.mockito.Mockito.mock(com.alibaba.cloud.ai.graph.streaming.StreamingOutput.class);
        org.mockito.Mockito.when(toolResult.message()).thenReturn(response);
        forwarder.graphEvent(toolResult);
        forwarder.graphEvent(hook(List.of(call("a"), call("b"))));
        assertThat(output).hasSize(5);
        assertThat(output.subList(0, 4)).extracting(NodeOutput::node).containsExactly("model_tool_fallback", "tool_started", "tool_finished", "tool_started");
        assertThat(output.getLast()).isSameAs(toolResult);

        List<NodeOutput> askOutput = new ArrayList<>();
        var ask = new MainAgentService.RuntimeEventForwarder(askOutput::add, "run", 1000);
        ask.questionCall("ask");
        ask.toolEvent(NodeOutput.of("ask_user", "main_agent", new OverAllState(Map.of("questionRequestId", "q")), null));
        var askResponse = org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(
                new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("ask", "ask_user", "{}"))).build();
        var askResult = org.mockito.Mockito.mock(com.alibaba.cloud.ai.graph.streaming.StreamingOutput.class);
        org.mockito.Mockito.when(askResult.message()).thenReturn(askResponse);
        ask.graphEvent(askResult);
        ask.graphEvent(hook(List.of(call("ask"))));
        assertThat(askOutput).hasSize(2);
        assertThat(askOutput.getFirst().node()).isEqualTo("model_tool_fallback");
        assertThat(askOutput.getLast()).isSameAs(askResult);
    }

    @Test
    void finishedRunDoesNotReleaseStaleQuestion() throws Exception {
        List<NodeOutput> output = new java.util.concurrent.CopyOnWriteArrayList<>();
        var forwarder = new MainAgentService.RuntimeEventForwarder(output::add, "run", 25);
        forwarder.questionCall("ask");
        forwarder.toolEvent(NodeOutput.of("ask_user", "main_agent", new OverAllState(Map.of("questionRequestId", "q")), null));
        forwarder.flush();
        Thread.sleep(80);
        assertThat(output).isEmpty();
    }

    @Test
    void timedOutQuestionIsReleasedAndLateHookCannotCorrectNextSegment() throws Exception {
        List<NodeOutput> output = new java.util.concurrent.CopyOnWriteArrayList<>();
        var released = new java.util.concurrent.CountDownLatch(1);
        var forwarder = new MainAgentService.RuntimeEventForwarder(event -> {
            output.add(event);
            if ("ask_user".equals(event.node())) released.countDown();
        }, "run", 25);
        forwarder.questionCall("ask");
        forwarder.toolEvent(NodeOutput.of("ask_user", "main_agent", new OverAllState(Map.of("questionRequestId", "q")), null));
        assertThat(released.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        // 迟到事件即使 ID 不匹配，也不能被当作下一轮文字。
        forwarder.graphEvent(hook(List.of(call("wrong-id"))));
        assertThat(output).extracting(NodeOutput::node).containsExactly("model_tool_fallback", "ask_user");
        forwarder.questionCall("next");
        forwarder.toolEvent(NodeOutput.of("ask_user", "main_agent", new OverAllState(Map.of("questionRequestId", "q2")), null));
        forwarder.graphEvent(hook(List.of(call("next"))));
        assertThat(output).extracting(NodeOutput::node).containsExactly("model_tool_fallback", "ask_user", "_AGENT_HOOK_message_recording.afterModel", "ask_user");
        forwarder.flush();
    }

    @Test
    void flushCancelsTimerAndQueueHeadCannotBlockOtherTools() throws Exception {
        List<NodeOutput> output = new java.util.concurrent.CopyOnWriteArrayList<>();
        var released = new java.util.concurrent.CountDownLatch(2);
        var forwarder = new MainAgentService.RuntimeEventForwarder(event -> {
            output.add(event);
            if ("tool_started".equals(event.node())) released.countDown();
        }, "run", 25);
        forwarder.toolEvent(tool("tool_started", "missing"));
        forwarder.toolEvent(tool("tool_started", "ready"));
        forwarder.graphEvent(hook(List.of(call("ready"))));
        assertThat(released.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(output).extracting(NodeOutput::node).containsExactly("_AGENT_HOOK_message_recording.afterModel", "model_tool_fallback", "tool_started", "tool_started");
        forwarder.toolEvent(tool("tool_started", "last"));
        forwarder.flush();
        int count = output.size();
        forwarder.toolEvent(tool("tool_started", "too-late"));
        forwarder.graphEvent(hook(List.of(call("too-late"))));
        Thread.sleep(80);
        assertThat(output).hasSize(count);
    }
    @Test
    void holdsToolsUntilMatchingModelEventAndKeepsOrder() {
        List<NodeOutput> output = new ArrayList<>();
        var forwarder = new MainAgentService.RuntimeEventForwarder(output::add);
        forwarder.toolEvent(tool("tool_started", "a"));
        forwarder.toolEvent(tool("tool_started", "b"));
        forwarder.toolEvent(tool("tool_finished", "a"));
        assertThat(output).isEmpty();
        forwarder.graphEvent(hook(List.of(call("a"), call("b"))));
        assertThat(output).extracting(NodeOutput::node).containsExactly(
                "_AGENT_HOOK_message_recording.afterModel", "tool_started", "tool_started", "tool_finished");
    }

    @Test
    void holdsQuestionUntilItsModelEventAndFlushesOnFailure() {
        List<NodeOutput> output = new ArrayList<>();
        var forwarder = new MainAgentService.RuntimeEventForwarder(output::add);
        forwarder.questionCall("ask");
        forwarder.toolEvent(NodeOutput.of("ask_user", "main_agent", new OverAllState(Map.of("questionRequestId", "q")), null));
        assertThat(output).isEmpty();
        forwarder.graphEvent(hook(List.of(call("ask"))));
        assertThat(output).extracting(NodeOutput::node).containsExactly("_AGENT_HOOK_message_recording.afterModel", "ask_user");
        forwarder.toolEvent(tool("tool_started", "other"));
        forwarder.flush();
        assertThat(output.getLast().node()).isEqualTo("tool_started");
    }

    private static AssistantMessage.ToolCall call(String id) {
        return new AssistantMessage.ToolCall(id, "function", "query_sql_data", "{}");
    }

    private static NodeOutput hook(List<AssistantMessage.ToolCall> calls) {
        var assistant = AssistantMessage.builder().content("思考").toolCalls(calls).build();
        return NodeOutput.of("_AGENT_HOOK_message_recording.afterModel", "main_agent", new OverAllState(Map.of("messages", List.of(assistant))), null);
    }

    private static NodeOutput tool(String node, String id) {
        return NodeOutput.of(node, "main_agent", new OverAllState(Map.of("toolCallId", id)), null);
    }
}
