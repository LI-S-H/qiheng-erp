package com.qiheng.erp.ai.order;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.action.PurchaseOrderActionReceipt;
import com.qiheng.erp.ai.agent.MainAgentService;
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.draft.PurchaseDraftReceipt;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.sales.SalesOrderDraftReceipt;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证通用确认服务：按工具选策略、校验动作、写文本和展示记录。
 *
 * @author Li
 * @since 2026-11-25
 */
class OrderConfirmationServiceTest {

    private static final String TOOL_CALL_ID = "call-1";

    @Test
    void salesDraftResolveWritesTextAndDisplayRecord() throws Exception {
        Fixture fixture = fixture("prepare_sales_order_draft", HITL_NODE);
        SalesOrderDraftReceipt receipt = new SalesOrderDraftReceipt(
                "created", "SO001", "9001", "DRAFT", null, null, null, List.of(), null);

        OrderConfirmationService.ResumedRun resumed = fixture.service()
                .resolveDraftForUser(1, 2, TOOL_CALL_ID, receipt);

        assertThat(resumed.runId()).isEqualTo("run-1");
        invokeApplier(fixture, resumed);

        ArgumentCaptor<Map<String, Object>> state = ArgumentCaptor.forClass(Map.class);
        verify(fixture.graph()).updateState(any(RunnableConfig.class), state.capture());
        ToolResponseMessage response = (ToolResponseMessage) ((List<?>) state.getValue().get("messages"))
                .getFirst();
        assertThat(response.getResponses().getFirst().responseData())
                .contains("用户批准了创建，销售单 SO001 已生成草稿。");

        ArgumentCaptor<AiMessage> saved = ArgumentCaptor.forClass(AiMessage.class);
        verify(fixture.store()).append(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo("TOOL_RESULT");
        assertThat(saved.getValue().getMessageKey()).isEqualTo("run-1:tool-result:" + TOOL_CALL_ID);
        assertThat(saved.getValue().getContent())
                .contains("created")
                .contains("9001")
                .contains("SO001");
    }

    @Test
    void purchaseActionResolveWritesConclusionText() throws Exception {
        Fixture fixture = fixture("submit_purchase_order", HITL_NODE);
        PurchaseOrderActionReceipt receipt = new PurchaseOrderActionReceipt(
                "submitted", "7001", "PO001", "SUBMITTED", null, null, "2026-12-02", null);

        OrderConfirmationService.ResumedRun resumed = fixture.service()
                .resolveActionForUser(1, 2, TOOL_CALL_ID, receipt);

        invokeApplier(fixture, resumed);

        ArgumentCaptor<Map<String, Object>> state = ArgumentCaptor.forClass(Map.class);
        verify(fixture.graph()).updateState(any(RunnableConfig.class), state.capture());
        ToolResponseMessage response = (ToolResponseMessage) ((List<?>) state.getValue().get("messages"))
                .getFirst();
        assertThat(response.getResponses().getFirst().responseData())
                .contains("用户同意提交，采购单 PO001 已提交审核。")
                .contains("预计到货：2026-12-02");
    }

    @Test
    void purchaseUpdateUsesCardBaselineForChangedFields() throws Exception {
        Fixture fixture = fixture("update_purchase_order_draft", HITL_NODE);
        Map<String, Object> baseline = purchaseParams("2026-12-03", "1");
        Map<String, Object> finalParams = purchaseParams("2026-12-05", "1");
        PurchaseDraftReceipt receipt = new PurchaseDraftReceipt(
                "edited", "PO001", "7001", "DRAFT", null,
                finalParams, "", List.of(), null, baseline);

        OrderConfirmationService.ResumedRun resumed = fixture.service()
                .resolveDraftForUser(1, 2, TOOL_CALL_ID, receipt);
        invokeApplier(fixture, resumed);

        ArgumentCaptor<Map<String, Object>> state = ArgumentCaptor.forClass(Map.class);
        verify(fixture.graph()).updateState(any(RunnableConfig.class), state.capture());
        ToolResponseMessage response = (ToolResponseMessage) ((List<?>) state.getValue().get("messages"))
                .getFirst();
        assertThat(response.getResponses().getFirst().responseData())
                .contains("用户修改了参数并保存，采购单 PO001 已更新。")
                .contains("修改的字段：expectedArrivalDate")
                .doesNotContain("items[");

        ArgumentCaptor<AiMessage> saved = ArgumentCaptor.forClass(AiMessage.class);
        verify(fixture.store()).append(saved.capture());
        assertThat(saved.getValue().getContent()).contains("changedFields")
                .contains("expectedArrivalDate");
    }

    /** 构造采购单业务参数；只改日期时用于验证基线比较。 */
    private Map<String, Object> purchaseParams(String arrivalDate, String quantity) {
        return Map.of(
                "supplierId", "5",
                "warehouseId", "6",
                "expectedArrivalDate", arrivalDate,
                "remark", "",
                "items", List.of(Map.of(
                        "productId", "4",
                        "quantity", new java.math.BigDecimal(quantity),
                        "unitPrice", new java.math.BigDecimal("10.00"),
                        "remark", "")));
    }

    @Test
    void expiredCheckpointIsRejected() throws Exception {
        Fixture fixture = fixture("prepare_sales_order_draft", "_AGENT_TOOL_");
        SalesOrderDraftReceipt receipt = new SalesOrderDraftReceipt(
                "created", "SO001", "9001", "DRAFT", null, null, null, List.of(), null);

        assertThatThrownBy(() -> fixture.service().resolveDraftForUser(1, 2, TOOL_CALL_ID, receipt))
                .isInstanceOf(MainAgentService.ConfirmationExpiredException.class);
    }

    @Test
    void statusNotMatchingActionIsRejected() throws Exception {
        Fixture fixture = fixture("update_sales_order_draft", HITL_NODE);
        SalesOrderDraftReceipt receipt = new SalesOrderDraftReceipt(
                "created", "SO001", "9001", "DRAFT", null, null, null, List.of(), null);

        assertThatThrownBy(() -> fixture.service().resolveDraftForUser(1, 2, TOOL_CALL_ID, receipt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("处置状态");
    }

    private static final String HITL_NODE = "_AGENT_HOOK_HITL";

    private Fixture fixture(String toolName, String nextNode) {
        ReactAgent agent = mock(ReactAgent.class);
        CompiledGraph graph = mock(CompiledGraph.class);
        when(agent.getAndCompileGraph()).thenReturn(graph);

        RedisSaver saver = mock(RedisSaver.class);
        Checkpoint checkpoint = checkpoint(toolName, nextNode);
        when(saver.get(any(RunnableConfig.class))).thenReturn(Optional.of(checkpoint));

        MainAgentService runs = mock(MainAgentService.class);
        when(runs.currentRunIdForUser(1, 2)).thenReturn("run-1");
        when(runs.resumeAfterConfirmation(eq(1L), eq(2L), eq("run-1"), any())).thenReturn(Flux.never());

        AiConversationMapper conversations = mock(AiConversationMapper.class);
        when(conversations.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        LoginUserService users = mock(LoginUserService.class);
        LoginUser user = new LoginUser();
        user.setPermissionCodes(List.of("ai:assistant:use"));
        when(users.findByUserId(1L)).thenReturn(user);
        ConversationMessageStore store = mock(ConversationMessageStore.class);

        OrderConfirmationService service = new OrderConfirmationService(agent, saver, runs, conversations,
                users, new ObjectMapper(), store,
                List.of(new SalesOrderConfirmationStrategy(), new PurchaseOrderConfirmationStrategy()));
        return new Fixture(service, runs, graph, store, checkpoint);
    }

    private Checkpoint checkpoint(String toolName, String nextNode) {
        AssistantMessage.ToolCall call = new AssistantMessage.ToolCall(TOOL_CALL_ID, "function", toolName, "{}");
        Checkpoint checkpoint = mock(Checkpoint.class);
        when(checkpoint.getNextNodeId()).thenReturn(nextNode);
        when(checkpoint.getId()).thenReturn("checkpoint");
        when(checkpoint.getState()).thenReturn(Map.of("messages", List.of(
                AssistantMessage.builder().toolCalls(List.of(call)).build())));
        return checkpoint;
    }

    @SuppressWarnings("unchecked")
    private void invokeApplier(Fixture fixture, OrderConfirmationService.ResumedRun resumed) throws Exception {
        ArgumentCaptor<MainAgentService.ConfirmationApplier> captor =
                ArgumentCaptor.forClass(MainAgentService.ConfirmationApplier.class);
        verify(fixture.runs()).resumeAfterConfirmation(eq(1L), eq(2L), eq(resumed.runId()), captor.capture());
        captor.getValue().apply(RunnableConfig.builder().threadId("main:1:2").build(), fixture.checkpoint());
    }

    private record Fixture(OrderConfirmationService service, MainAgentService runs, CompiledGraph graph,
                           ConversationMessageStore store, Checkpoint checkpoint) {
    }
}