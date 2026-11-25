package com.qiheng.erp.ai.sales;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.agent.MainAgentService;
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SalesOrderConfirmationServiceTest {

    private static final AssistantMessage.ToolCall CALL =
            new AssistantMessage.ToolCall("c1", "function", "prepare_sales_order_draft", "{}");

    /** 同一份测试依赖，避免用反射取私有字段。 */
    private record Fixture(SalesOrderConfirmationService service, ReactAgent agent,
                           ConversationMessageStore store) {
    }

    @Test
    void resolveReusesOriginalRunIdAndPassesApplier() throws Exception {
        checkRunId("original");
        checkRunId(null);
    }

    private void checkRunId(String original) throws Exception {
        var saver = mock(RedisSaver.class);
        var checkpoint = checkpoint();
        when(saver.get(any(RunnableConfig.class))).thenReturn(Optional.of(checkpoint));
        var runs = mock(MainAgentService.class);
        when(runs.currentRunIdForUser(1, 2)).thenReturn(original);
        when(runs.resumeAfterConfirmation(eq(1L), eq(2L), anyString(), any())).thenReturn(Flux.never());
        var fixture = fixture(runs, saver);

        var receipt = new SalesOrderDraftReceipt("created", null, null, null, null, null, null, null, null);
        var resumed = fixture.service().resolveForUser(1, 2, "c1", receipt);

        if (original == null) {
            assertThat(UUID.fromString(resumed.runId())).isNotNull();
        } else {
            assertThat(resumed.runId()).isEqualTo(original);
        }
        verify(runs).resumeAfterConfirmation(eq(1L), eq(2L), eq(resumed.runId()), any());
    }

    @Test
    void applierWritesToolResponseAndVisibleRecord() throws Exception {
        var saver = mock(RedisSaver.class);
        var checkpoint = checkpoint();
        when(saver.get(any(RunnableConfig.class))).thenReturn(Optional.of(checkpoint));
        var runs = mock(MainAgentService.class);
        when(runs.currentRunIdForUser(1, 2)).thenReturn("run-1");
        when(runs.resumeAfterConfirmation(eq(1L), eq(2L), anyString(), any())).thenReturn(Flux.never());
        var fixture = fixture(runs, saver);

        var receipt = new SalesOrderDraftReceipt("created", "SO001", "9001", "DRAFT", null, null, null, null, null);
        fixture.service().resolveForUser(1, 2, "c1", receipt);

        var applier = ArgumentCaptor.forClass(MainAgentService.ConfirmationApplier.class);
        verify(runs).resumeAfterConfirmation(eq(1L), eq(2L), eq("run-1"), applier.capture());
        applier.getValue().apply(RunnableConfig.builder().threadId("main:1:2").build(), checkpoint);

        var graph = fixture.agent().getAndCompileGraph();
        var state = ArgumentCaptor.forClass(Map.class);
        verify(graph).updateState(any(RunnableConfig.class), state.capture());
        var response = (ToolResponseMessage) ((List<?>) state.getValue().get("messages")).getFirst();
        assertThat(response.getResponses()).hasSize(1);
        assertThat(response.getResponses().getFirst().id()).isEqualTo("c1");
        assertThat(response.getResponses().getFirst().responseData()).contains("\"salesOrderId\":\"9001\"");

        var saved = ArgumentCaptor.forClass(AiMessage.class);
        verify(fixture.store()).append(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo("TOOL_RESULT");
        assertThat(saved.getValue().getRequestId()).isEqualTo("run-1");
        assertThat(saved.getValue().getMessageKey()).isEqualTo("run-1:tool-result:c1");
        assertThat(saved.getValue().getContent()).contains("created").contains("SO001");
    }

    @Test
    void resolveWhenCheckpointLeftConfirmationNodeReportsExpired() throws Exception {
        var saver = mock(RedisSaver.class);
        var checkpoint = mock(Checkpoint.class);
        when(checkpoint.getNextNodeId()).thenReturn("_AGENT_TOOL_");
        when(saver.get(any(RunnableConfig.class))).thenReturn(Optional.of(checkpoint));
        var fixture = fixture(mock(MainAgentService.class), saver);

        var receipt = new SalesOrderDraftReceipt("created", null, null, null, null, null, null, null, null);
        assertThatThrownBy(() -> fixture.service().resolveForUser(1, 2, "c1", receipt))
                .isInstanceOf(MainAgentService.ConfirmationExpiredException.class)
                .hasMessage("确认已失效，请重新发起");
    }

    private Checkpoint checkpoint() {
        var checkpoint = mock(Checkpoint.class);
        when(checkpoint.getNextNodeId()).thenReturn("_AGENT_HOOK_HITL");
        when(checkpoint.getId()).thenReturn("checkpoint");
        when(checkpoint.getState()).thenReturn(Map.of("messages", List.of(
                AssistantMessage.builder().toolCalls(List.of(CALL)).build())));
        return checkpoint;
    }

    private Fixture fixture(MainAgentService runs, RedisSaver saver) {
        var agent = mock(ReactAgent.class);
        when(agent.getAndCompileGraph()).thenReturn(mock(CompiledGraph.class));
        var conversations = mock(AiConversationMapper.class);
        when(conversations.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var users = mock(LoginUserService.class);
        var user = new LoginUser();
        user.setPermissionCodes(List.of("ai:assistant:use"));
        when(users.findByUserId(1L)).thenReturn(user);
        var store = mock(ConversationMessageStore.class);
        return new Fixture(new SalesOrderConfirmationService(agent, saver, runs, conversations, users,
                new ObjectMapper(), store), agent, store);
    }
}