package com.qiheng.erp.ai.controller;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.qiheng.erp.ai.clarification.ClarificationConfirmationService;
import com.qiheng.erp.ai.agent.MainAgentService;
import com.qiheng.erp.ai.order.OrderConfirmationService;
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

    @Test
    void confirmationTextIsSentBeforeApprovalWhenModelHookIsMissing() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var text = NodeOutput.of("confirmation_text", "main_agent", new OverAllState(Map.of("callId", "sales-1", "text", "请确认草稿")), null);
        var pending = NodeOutput.of("sales_order_draft", "main_agent", new OverAllState(Map.of("callId", "sales-1")), null);
        when(agent.streamForCurrentUser(eq(1L), eq("创建"), anyString())).thenReturn(Flux.just(text, pending));
        var events = new MainAgentController(agents, null).stream("1", new MainAgentController.MessageRequest("创建"))
                .filter(e -> !"run_snapshot".equals(e.event())).collectList().block();
        assertThat(events).extracting(e -> e.event()).containsExactly("text_completed", "sales_order_draft", "run_completed");
        assertThat(((Map<?, ?>) events.getFirst().data()).get("text")).isEqualTo("请确认草稿");
    }

    @Test
    void purchaseApprovalPausesWithWaitingUser() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var draft = NodeOutput.of("purchase_draft", "main_agent", new OverAllState(Map.of("callId", "p1")), null);
        var action = NodeOutput.of("purchase_action", "main_agent", new OverAllState(Map.of("callId", "p2")), null);
        when(agent.streamForCurrentUser(eq(1L), eq("采购"), anyString())).thenReturn(Flux.just(draft, action));
        var events = new MainAgentController(agents, null)
                .stream("1", new MainAgentController.MessageRequest("采购"))
                .filter(e -> !"run_snapshot".equals(e.event()))
                .collectList()
                .block();

        assertThat(events).extracting(e -> e.event())
                .containsExactly("purchase_draft", "purchase_action", "run_completed");
        assertThat(((Map<?, ?>) events.getLast().data()).get("status")).isEqualTo("WAITING_USER");
    }

    @Test
    void confirmationTextAlreadySentByHookIsNotRepeated() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var call = new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("sales-1", "function", "prepare_sales_order_draft", "{}");
        var assistant = org.springframework.ai.chat.messages.AssistantMessage.builder().content("请确认草稿").toolCalls(List.of(call)).build();
        var hook = NodeOutput.of("_AGENT_HOOK_message_recording.afterModel", "main_agent", new OverAllState(Map.of("messages", List.of(assistant))), null);
        var text = NodeOutput.of("confirmation_text", "main_agent", new OverAllState(Map.of("callId", "sales-1", "text", "请确认草稿")), null);
        var pending = NodeOutput.of("sales_order_draft", "main_agent", new OverAllState(Map.of("callId", "sales-1")), null);
        when(agent.streamForCurrentUser(eq(1L), eq("创建"), anyString())).thenReturn(Flux.just(hook, text, pending));
        var events = new MainAgentController(agents, null).stream("1", new MainAgentController.MessageRequest("创建"))
                .filter(e -> !"run_snapshot".equals(e.event())).collectList().block();
        assertThat(events).extracting(e -> e.event()).containsExactly("text_completed", "sales_order_draft", "run_completed");
    }

    @Test
    void confirmationFallbackCorrectsOriginalStreamSegment() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var fragment = mock(StreamingOutput.class);
        when(fragment.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(fragment.chunk()).thenReturn("请确认");
        var boundary = NodeOutput.of("model_tool_fallback", "main_agent", new OverAllState(Map.of("callId", "sales-1")), null);
        var text = NodeOutput.of("confirmation_text", "main_agent", new OverAllState(Map.of("callId", "sales-1", "text", "请确认草稿")), null);
        var pending = NodeOutput.of("sales_order_draft", "main_agent", new OverAllState(Map.of("callId", "sales-1")), null);
        when(agent.streamForCurrentUser(eq(1L), eq("创建"), anyString())).thenReturn(Flux.just(fragment, boundary, text, pending));
        var events = new MainAgentController(agents, null).stream("1", new MainAgentController.MessageRequest("创建"))
                .filter(e -> !"run_snapshot".equals(e.event())).collectList().block();
        assertThat(events).extracting(e -> e.event()).containsExactly("text_delta", "text_completed", "sales_order_draft", "run_completed");
        assertThat(((Map<?, ?>) events.get(0).data()).get("messageId"))
                .isEqualTo(((Map<?, ?>) events.get(1).data()).get("messageId"));
    }

    @Test
    void resumedRunSnapshotRetainsConfirmationTextWhenHookIsMissing() throws Exception {
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<OrderConfirmationService> sales = mock(ObjectProvider.class);
        var service = mock(OrderConfirmationService.class);
        when(agents.getObject()).thenReturn(mock(MainAgentService.class));
        when(sales.getIfAvailable()).thenReturn(service);
        when(sales.getObject()).thenReturn(service);
        var text = NodeOutput.of("confirmation_text", "main_agent", new OverAllState(Map.of("callId", "sales-2", "text", "请确认新修改")), null);
        var pending = NodeOutput.of("sales_order_draft", "main_agent", new OverAllState(Map.of("callId", "sales-2")), null);
        when(service.resolveDraftForUser(eq(1L), eq(1L), eq("draft"), any()))
                .thenReturn(new OrderConfirmationService.ResumedRun("original", Flux.just(text, pending)));
        var controller = new MainAgentController(agents, null, sales);
        var draft = new com.qiheng.erp.ai.sales.SalesOrderDraftReceipt("created", null, null, null, null, null, null, null, null);
        controller.resolveSalesDraft("1", "draft", draft);
        var snapshot = controller.runtime("1").getData();
        assertThat(snapshot.get("items").toString()).contains("请确认新修改");
        assertThat(snapshot.get("status")).isEqualTo("WAITING_USER");
    }

    @Test
    void resumedSalesSnapshotUsesOriginalRunIdBeforeAnyGraphEvent() throws Exception {
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<OrderConfirmationService> sales = mock(ObjectProvider.class);
        var service = mock(OrderConfirmationService.class);
        when(agents.getObject()).thenReturn(mock(MainAgentService.class));
        when(sales.getIfAvailable()).thenReturn(service);
        when(sales.getObject()).thenReturn(service);
        when(service.resolveDraftForUser(eq(1L), eq(1L), eq("draft"), any()))
                .thenReturn(new OrderConfirmationService.ResumedRun("original", Flux.never()));
        when(service.resolveActionForUser(eq(1L), eq(1L), eq("action"), any()))
                .thenReturn(new OrderConfirmationService.ResumedRun("fallback", Flux.never()));
        var controller = new MainAgentController(agents, null, sales);
        var draft = new com.qiheng.erp.ai.sales.SalesOrderDraftReceipt("created", null, null, null, null, null, null, null, null);
        var action = mock(com.qiheng.erp.ai.sales.SalesOrderActionReceipt.class);

        controller.resolveSalesDraft("1", "draft", draft);
        assertThat(controller.runtime("1").getData()).containsEntry("runId", "original");
        controller.resolveSalesAction("1", "action", action);
        assertThat(controller.runtime("1").getData()).containsEntry("runId", "fallback");
    }

    /** 框架异常结束时主动终止订阅，不能让页面一直等待。 */
    @Test
    void producerWithoutTerminalEventReportsFailure() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ClarificationConfirmationService> clarifications = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        when(agent.streamForCurrentUser(eq(1L), eq("查询"), anyString())).thenReturn(Flux.empty());
        var events = new MainAgentController(agents, clarifications).stream("1", new MainAgentController.MessageRequest("查询")).collectList().block();
        assertThat(events).extracting(e -> e.event()).containsExactly("run_snapshot", "run_failed");
    }

    @Test
    void fallbackStartsNewSegmentWithoutExposingInternalEvent() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ClarificationConfirmationService> clarifications = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var fragment = mock(StreamingOutput.class);
        when(fragment.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(fragment.chunk()).thenReturn("说明");
        var fallback = NodeOutput.of("model_tool_fallback", "main_agent", new OverAllState(Map.of()), null);
        var question = NodeOutput.of("question", "main_agent", new OverAllState(Map.of("questionRequestId", "q", "questions", List.of())), null);
        when(agent.streamForCurrentUser(eq(1L), eq("查询"), anyString())).thenReturn(Flux.just(fragment, fallback, question, fragment));
        var events = new MainAgentController(agents, clarifications).stream("1", new MainAgentController.MessageRequest("查询")).filter(e -> !"run_snapshot".equals(e.event()) && !"run_failed".equals(e.event())).collectList().block();
        assertThat(events).extracting(e -> e.event()).containsExactly("text_delta", "question", "text_delta", "run_completed");
        assertThat(((Map<?, ?>) events.get(0).data()).get("messageId")).isNotEqualTo(((Map<?, ?>) events.get(2).data()).get("messageId"));
    }

    /** 追问进入 HITL 后必须暂停本轮并发送 WAITING_USER，而不是继续消费后续事件。 */
    @Test
    void hitlQuestionPausesRunWithWaitingStatus() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ClarificationConfirmationService> clarifications = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var fragment = mock(StreamingOutput.class);
        when(fragment.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(fragment.chunk()).thenReturn("说明");
        var question = NodeOutput.of("question", "main_agent",
                new OverAllState(Map.of("questionRequestId", "q", "questions", List.of())), null);
        when(agent.streamForCurrentUser(eq(1L), eq("查询"), anyString())).thenReturn(Flux.just(fragment, question));

        var events = new MainAgentController(agents, clarifications)
                .stream("1", new MainAgentController.MessageRequest("查询"))
                .filter(e -> !"run_snapshot".equals(e.event()))
                .collectList().block();

        assertThat(events).extracting(e -> e.event()).containsExactly("text_delta", "question", "run_completed");
        assertThat(((Map<?, ?>) events.get(2).data()).get("status")).isEqualTo("WAITING_USER");
    }

    /** 追问检查点过期应转为业务错误，而不是 500。 */
    @Test
    void expiredAnswerReturnsBusinessError() throws Exception {
        var service = mock(ClarificationConfirmationService.class);
        ObjectProvider<ClarificationConfirmationService> clarifications = mock(ObjectProvider.class);
        when(clarifications.getIfAvailable()).thenReturn(service);
        when(service.resolveForUser(eq(1L), eq(1L), eq("q"), any(), any()))
                .thenThrow(new MainAgentService.ConfirmationExpiredException());
        var controller = new MainAgentController(mock(ObjectProvider.class), clarifications);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.answer("1", "q",
                        new MainAgentController.AnswerRequest(List.of(
                                new com.qiheng.erp.ai.clarification.ClarificationAnswer("q", List.of("A"), "")), null)))
                .isInstanceOf(com.qiheng.erp.common.exception.BizException.class)
                .hasMessageContaining("确认已失效");
    }

    /** 答案校验失败应保留具体中文原因，而不是 500。 */
    @Test
    void invalidAnswerReturnsBusinessError() throws Exception {
        var service = mock(ClarificationConfirmationService.class);
        ObjectProvider<ClarificationConfirmationService> clarifications = mock(ObjectProvider.class);
        when(clarifications.getIfAvailable()).thenReturn(service);
        when(service.resolveForUser(eq(1L), eq(1L), eq("q"), any(), any()))
                .thenThrow(new IllegalArgumentException("回答不符合要求：选项不在候选中"));
        var controller = new MainAgentController(mock(ObjectProvider.class), clarifications);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> controller.answer("1", "q",
                        new MainAgentController.AnswerRequest(List.of(
                                new com.qiheng.erp.ai.clarification.ClarificationAnswer("q", List.of("A"), "")), null)))
                .isInstanceOf(com.qiheng.erp.common.exception.BizException.class)
                .hasMessageContaining("选项不在候选中");
    }

    /** 非法追问尝试的 assistant 文字不能进入实时消息。 */
    @Test
    void invalidAskUserAssistantTextIsSuppressed() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ClarificationConfirmationService> clarifications = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var start = NodeOutput.of("run_started", "main_agent", new OverAllState(Map.of("runId", "r")), null);
        var call = new org.springframework.ai.chat.messages.AssistantMessage.ToolCall(
                "call-bad", "function", "ask_user", "{}");
        var hook = NodeOutput.of("message_recording.afterModel", "main_agent", new OverAllState(Map.of(
                "messages", List.of(org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .content("需要补充条件").toolCalls(List.of(call)).build()),
                com.qiheng.erp.ai.agent.AskUserValidationHook.INVALID_CALL_KEY, "call-bad")), null);
        when(agent.streamForCurrentUser(eq(1L), eq("查询"), anyString())).thenReturn(Flux.just(start, hook));

        var events = new MainAgentController(agents, clarifications)
                .stream("1", new MainAgentController.MessageRequest("查询"))
                .filter(e -> !"run_snapshot".equals(e.event()))
                .collectList().block();

        assertThat(events).extracting(e -> e.event()).doesNotContain("text_completed");
    }

    /** 上一轮遗留的失败标记不能污染新的运行。 */
    @Test
    void previousValidationFailureDoesNotFailNextRun() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ClarificationConfirmationService> clarifications = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var start = NodeOutput.of("run_started", "main_agent", new OverAllState(Map.of("runId", "r2")), null);
        var fragment = mock(StreamingOutput.class);
        when(fragment.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(fragment.chunk()).thenReturn("说明");
        var end = mock(NodeOutput.class);
        when(end.isEND()).thenReturn(true);
        when(end.state()).thenReturn(new OverAllState(Map.of(
                "messages", List.of(new org.springframework.ai.chat.messages.AssistantMessage("本轮正常完成")),
                com.qiheng.erp.ai.agent.AskUserValidationHook.FAILED_STATE_KEY, "r1")));
        when(agent.streamForCurrentUser(eq(1L), eq("查询"), anyString())).thenReturn(Flux.just(start, fragment, end));

        var events = new MainAgentController(agents, clarifications)
                .stream("1", new MainAgentController.MessageRequest("查询"))
                .filter(e -> !"run_snapshot".equals(e.event()))
                .collectList().block();

        assertThat(events).extracting(e -> e.event())
                .containsExactly("run_started", "text_delta", "text_completed", "run_completed");
    }

    /** 追问参数连续非法终止时，先给最终说明再发失败事件。 */
    @Test
    void askUserValidationFailureEmitsFinalTextThenFailed() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ClarificationConfirmationService> clarifications = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var fragment = mock(StreamingOutput.class);
        when(fragment.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(fragment.chunk()).thenReturn("说明");
        var end = mock(NodeOutput.class);
        when(end.isEND()).thenReturn(true);
        when(end.state()).thenReturn(new OverAllState(Map.of(
                "messages", List.of(new org.springframework.ai.chat.messages.AssistantMessage("抱歉，这次没能把需要补充的条件问清楚。")),
                com.qiheng.erp.ai.agent.AskUserValidationHook.FAILED_STATE_KEY, "r1")));
        var start = NodeOutput.of("run_started", "main_agent", new OverAllState(Map.of("runId", "r1")), null);
        when(agent.streamForCurrentUser(eq(1L), eq("查询"), anyString())).thenReturn(Flux.just(start, fragment, end));

        var events = new MainAgentController(agents, clarifications)
                .stream("1", new MainAgentController.MessageRequest("查询"))
                .filter(e -> !"run_snapshot".equals(e.event()))
                .collectList().block();

        assertThat(events).extracting(e -> e.event()).containsExactly("run_started", "text_delta", "text_completed", "run_failed");
        assertThat(((Map<?, ?>) events.get(3).data()).get("code")).isEqualTo("ASK_USER_VALIDATION_FAILED");
        assertThat(((Map<?, ?>) events.get(2).data()).get("text")).isEqualTo("抱歉，这次没能把需要补充的条件问清楚。");
    }
    /** 工具事件来自另一条回调，先抵达时不能提前切换文字段。 */
    @Test
    void earlyToolEventDoesNotMoveCompletionToNextSegment() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ClarificationConfirmationService> clarifications = mock(ObjectProvider.class);
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
        ObjectProvider<ClarificationConfirmationService> clarifications = mock(ObjectProvider.class);
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
        ObjectProvider<ClarificationConfirmationService> clarifications = mock(ObjectProvider.class);
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
        ObjectProvider<ClarificationConfirmationService> clarifications = mock(ObjectProvider.class);
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
        ObjectProvider<ClarificationConfirmationService> clarifications = mock(ObjectProvider.class);
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
        ObjectProvider<ClarificationConfirmationService> clarifications = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var start = NodeOutput.of("run_started", "main_agent", new OverAllState(Map.of("runId", "r", "conversationId", "1")), null);
        var fragment = mock(StreamingOutput.class);
        when(fragment.getOutputType()).thenReturn(OutputType.AGENT_MODEL_STREAMING);
        when(fragment.chunk()).thenReturn("文字");
        var question = NodeOutput.of("question", "main_agent", new OverAllState(Map.of("questionRequestId", "q", "questions", List.of())), null);
        when(agent.streamForCurrentUser(eq(1L), eq("查询"), anyString())).thenReturn(Flux.just(start, fragment, modelHook("文字"), question, fragment));
        var events = new MainAgentController(agents, clarifications).stream("1", new MainAgentController.MessageRequest("查询"))
                .filter(e -> !"run_snapshot".equals(e.event()) && !"run_failed".equals(e.event())).collectList().block();
        var before = (Map<?, ?>) events.get(1).data();
        var after = (Map<?, ?>) events.get(4).data();
        assertThat(before.get("messageId")).isNotEqualTo(after.get("messageId"));
    }

    /** 模型调用失败时不能把框架错误文本当作答复，改发带错误码的失败事件。 */
    @Test
    void modelFailureEmitsRunFailedWithCodeInsteadOfAnswer() throws Exception {
        var agent = mock(MainAgentService.class);
        ObjectProvider<MainAgentService> agents = mock(ObjectProvider.class);
        ObjectProvider<ClarificationConfirmationService> clarifications = mock(ObjectProvider.class);
        when(agents.getObject()).thenReturn(agent);
        var start = NodeOutput.of("run_started", "main_agent", new OverAllState(Map.of("runId", "r")), null);
        var end = mock(NodeOutput.class);
        when(end.isEND()).thenReturn(true);
        when(end.state()).thenReturn(new OverAllState(Map.of("messages",
                List.of(new org.springframework.ai.chat.messages.AssistantMessage("Exception: 上游超时")))));
        when(agent.streamForCurrentUser(eq(1L), eq("查询"), anyString())).thenReturn(Flux.just(start, end));

        var events = new MainAgentController(agents, clarifications)
                .stream("1", new MainAgentController.MessageRequest("查询"))
                .filter(e -> !"run_snapshot".equals(e.event())).collectList().block();

        assertThat(events).extracting(e -> e.event()).containsExactly("run_started", "run_failed");
        var failure = (Map<?, ?>) events.get(1).data();
        assertThat(failure.get("code")).isEqualTo("MODEL_CALL_FAILED");
        assertThat(failure.get("message")).isEqualTo("模型服务暂时不可用，请稍后重试。");
        assertThat(failure.get("message").toString()).doesNotContain("Exception");
    }

    private NodeOutput modelHook(String text) {
        var call = new org.springframework.ai.chat.messages.AssistantMessage.ToolCall("call", "function", "query_sql_data", "{}");
        var assistant = org.springframework.ai.chat.messages.AssistantMessage.builder().content(text).toolCalls(List.of(call)).build();
        return NodeOutput.of("_AGENT_HOOK_message_recording.afterModel", "main_agent", new OverAllState(Map.of("messages", List.of(assistant))), null);
    }
}
