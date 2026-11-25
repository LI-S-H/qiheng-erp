package com.qiheng.erp.ai.sales;

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
import org.springframework.ai.chat.messages.AssistantMessage;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SalesOrderConfirmationServiceTest {
    @Test
    void existingAndFallbackRunIdsAreSharedByResultAndResume() throws Exception {
        checkRunId("original");
        checkRunId(null);
    }

    private void checkRunId(String original) throws Exception {
        var agent = mock(ReactAgent.class);
        when(agent.getAndCompileGraph()).thenReturn(mock(com.alibaba.cloud.ai.graph.CompiledGraph.class));
        var saver = mock(RedisSaver.class);
        var checkpoint = mock(Checkpoint.class);
        var call = new AssistantMessage.ToolCall("c1", "function", "prepare_sales_order_draft", "{}");
        when(checkpoint.getNextNodeId()).thenReturn("_AGENT_HOOK_HITL");
        when(checkpoint.getId()).thenReturn("checkpoint");
        when(checkpoint.getState()).thenReturn(Map.of("messages", List.of(AssistantMessage.builder().toolCalls(List.of(call)).build())));
        when(saver.get(any(RunnableConfig.class))).thenReturn(Optional.of(checkpoint));
        var runs = mock(MainAgentService.class);
        when(runs.currentRunIdForUser(1, 2)).thenReturn(original);
        when(runs.resumeForUser(eq(1L), eq(2L), anyString())).thenReturn(Flux.never());
        var conversations = mock(AiConversationMapper.class);
        when(conversations.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var users = mock(LoginUserService.class);
        var user = new LoginUser();
        user.setPermissionCodes(List.of("ai:assistant:use"));
        when(users.findByUserId(1L)).thenReturn(user);
        var store = mock(ConversationMessageStore.class);
        var service = new SalesOrderConfirmationService(agent, saver, runs, conversations, users, new ObjectMapper(), store);

        var receipt = new SalesOrderDraftReceipt("created", null, null, null, null, null, null, null, null);
        var resumed = service.resolveForUser(1, 2, "c1", receipt);

        if (original == null) assertThat(UUID.fromString(resumed.runId())).isNotNull();
        else assertThat(resumed.runId()).isEqualTo(original);
        verify(runs).resumeForUser(1, 2, resumed.runId());
        var saved = org.mockito.ArgumentCaptor.forClass(AiMessage.class);
        verify(store).append(saved.capture());
        assertThat(saved.getValue().getRequestId()).isEqualTo(resumed.runId());
    }
}
