package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.conversation.ConversationExecutionLock;
import com.qiheng.erp.ai.sql.tool.SqlToolContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.misc.CompletableFutureWrapper;
import reactor.core.publisher.Flux;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.chat.messages.AssistantMessage;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证会话归属和可信身份在流启动前被固定。 */
class MainAgentServiceTest {
    @Test
    void pairsInterruptedToolBeforeContinuingAndChecksOwnership() throws Exception {
        var agent = mock(ReactAgent.class);
        var saver = mock(RedisSaver.class);
        var conversations = mock(AiConversationMapper.class);
        when(conversations.selectOwned(101L, 1L)).thenReturn(new AiConversation());
        var users = mock(LoginUserService.class);
        var user = new LoginUser(); user.setPermissionCodes(List.of("ai:assistant:use"));
        when(users.findByUserId(1L)).thenReturn(user);
        var locks = mock(ConversationExecutionLock.class);
        var lock = mock(RLock.class);
        when(locks.of(101)).thenReturn(lock);
        when(lock.tryLockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>(true));
        when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>((Void) null));
        var checkpoint = mock(Checkpoint.class);
        when(checkpoint.getNextNodeId()).thenReturn("_AGENT_TOOL_");
        var call = new AssistantMessage.ToolCall("call1", "function", "query_sql_data", "{}");
        when(checkpoint.getState()).thenReturn(Map.of("messages", List.of(
                AssistantMessage.builder().toolCalls(List.of(call)).build())));
        when(saver.get(any(RunnableConfig.class))).thenReturn(Optional.of(checkpoint));
        var graph = mock(com.alibaba.cloud.ai.graph.CompiledGraph.class);
        when(agent.getAndCompileGraph()).thenReturn(graph);
        var end = endOutput();
        when(agent.stream(anyString(), any(RunnableConfig.class))).thenReturn(Flux.just(end));
        var service = new MainAgentService(agent, saver, conversations, users, locks, mock(com.qiheng.erp.ai.conversation.ConversationMessageStore.class), new com.fasterxml.jackson.databind.ObjectMapper());

        service.streamForUser(1, 101, "继续").blockLast();
        var update = ArgumentCaptor.forClass(Map.class);
        verify(graph).updateState(any(RunnableConfig.class), update.capture());
        var result = (org.springframework.ai.chat.messages.ToolResponseMessage) ((List<?>) update.getValue().get("messages")).getFirst();
        assertThat(result.getResponses().getFirst().id()).isEqualTo("call1");
        assertThat(result.getResponses().getFirst().responseData()).contains("INTERRUPTED");
        assertThatThrownBy(() -> service.streamForUser(2, 101, "继续").blockLast())
                .hasMessage("会话不存在或无权访问");
        when(lock.tryLockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>(false));
        assertThatThrownBy(() -> service.streamForUser(1, 101, "继续").blockLast())
                .hasMessage("会话正在执行其他任务");
        verify(agent).stream(eq("继续"), any(RunnableConfig.class));
    }
    @Test
    void validatesOwnershipAndPassesTrustedIdentity() throws Exception {
        var agent = mock(ReactAgent.class);
        var conversations = mock(AiConversationMapper.class);
        when(conversations.selectOwned(101L, 1L)).thenReturn(new AiConversation());
        var end = endOutput();
        when(agent.stream(anyString(), any(RunnableConfig.class))).thenReturn(Flux.just(end));
        var users = mock(LoginUserService.class);
        var user = new LoginUser(); user.setPermissionCodes(List.of("ai:assistant:use"));
        when(users.findByUserId(1L)).thenReturn(user);
        var locks = mock(ConversationExecutionLock.class);
        var lock = mock(RLock.class);
        when(locks.of(101)).thenReturn(lock);
        when(lock.tryLockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>(true));
        when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>((Void) null));
        var store = mock(com.qiheng.erp.ai.conversation.ConversationMessageStore.class);
        var saver = mock(RedisSaver.class);
        var service = new MainAgentService(agent, saver, conversations, users, locks, store, new com.fasterxml.jackson.databind.ObjectMapper());

        user.setUserId(1L);
        try (var context = mockStatic(com.qiheng.erp.security.context.UserContext.class)) {
            context.when(com.qiheng.erp.security.context.UserContext::requireCurrentUser).thenReturn(user);
            var stream = service.streamForCurrentUser(101, "查询库存");
            context.verify(com.qiheng.erp.security.context.UserContext::requireCurrentUser);
            stream.blockLast();
        }
        assertThat(service.currentForUser(1, 101).status()).isEqualTo("COMPLETED");
        assertThatThrownBy(() -> service.currentForUser(2, 101)).hasMessage("会话不存在或无权访问");
        service.streamForUser(1, 101, "继续").blockLast();
        verify(agent).stream(eq("继续"), any(RunnableConfig.class));
        var config = ArgumentCaptor.forClass(RunnableConfig.class);
        verify(agent).stream(eq("查询库存"), config.capture());
        assertThat(config.getValue().threadId()).hasValue("main:1:101");
        var trusted = (SqlToolContext) config.getValue().metadata(TrustedToolContextInterceptor.TRUSTED_CONTEXT).orElseThrow();
        assertThat(trusted.userId()).isEqualTo(1);
        assertThat(trusted.conversationId()).isEqualTo(101);
        assertThat(trusted.businessDate()).isNotBlank();
        assertThatThrownBy(() -> service.streamForUser(2, 101, "查询库存").blockLast())
                .hasMessage("会话不存在或无权访问");
        user.setPermissionCodes(List.of());
        assertThatThrownBy(() -> service.streamForUser(1, 101, "查询库存").blockLast())
                .hasMessage("当前用户无 AI 使用权限");
        user.setPermissionCodes(List.of("ai:assistant:use"));
        when(agent.stream(eq("执行失败"), any(RunnableConfig.class)))
                .thenReturn(Flux.error(new IllegalStateException("模型调用失败")));
        when(saver.get(any(RunnableConfig.class))).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.streamForUser(1, 101, "执行失败").blockLast())
                .hasMessage("模型调用失败");
        assertThat(service.currentForUser(1, 101).status()).isEqualTo("FAILED");
        verify(store, times(2)).updateRunStatus(eq(1L), eq(101L), anyString(), eq("COMPLETED"), isNull());
        verify(store).updateRunStatus(eq(1L), eq(101L), anyString(), eq("FAILED"), eq("AGENT_EXECUTION_FAILED"));
        verify(lock, times(5)).unlockAsync(anyLong());
        verify(agent).stream(eq("执行失败"), any(RunnableConfig.class));
        verifyNoMoreInteractions(agent);
    }

    @Test
    void sendsNewInputWhenQuestionWasInterrupted() throws Exception {
        var conversations = mock(AiConversationMapper.class);
        when(conversations.selectOwned(101L, 1L)).thenReturn(new AiConversation());
        var users = mock(LoginUserService.class);
        var user = new LoginUser();
        user.setPermissionCodes(List.of("ai:assistant:use"));
        when(users.findByUserId(1L)).thenReturn(user);
        var locks = mock(ConversationExecutionLock.class);
        var lock = mock(RLock.class);
        when(locks.of(101)).thenReturn(lock);
        when(lock.tryLockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>(true));
        when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>((Void) null));
        var checkpoint = mock(Checkpoint.class);
        when(checkpoint.getNextNodeId()).thenReturn("_AGENT_TOOL_");
        var call = new AssistantMessage.ToolCall("ask1", "function", "ask_user", "{}");
        when(checkpoint.getState()).thenReturn(Map.of("messages", List.of(
                AssistantMessage.builder().toolCalls(List.of(call)).build())));
        var saver = mock(RedisSaver.class);
        when(saver.get(any(RunnableConfig.class))).thenReturn(Optional.of(checkpoint));
        var agent = mock(ReactAgent.class);
        when(agent.getAndCompileGraph()).thenReturn(mock(com.alibaba.cloud.ai.graph.CompiledGraph.class));
        var end = endOutput();
        when(agent.stream(anyString(), any(RunnableConfig.class))).thenReturn(Flux.just(end));
        var service = new MainAgentService(agent, saver, conversations, users, locks, mock(com.qiheng.erp.ai.conversation.ConversationMessageStore.class), new com.fasterxml.jackson.databind.ObjectMapper());

        service.streamForUser(1, 101, "继续，查一号仓库").blockLast();
        verify(agent).stream(eq("继续，查一号仓库"), any(RunnableConfig.class));
        verify(agent).getAndCompileGraph();
    }

    @Test
    void acceptsPreviouslyRecordedInterruptedResult() throws Exception {
        var conversations = mock(AiConversationMapper.class);
        when(conversations.selectOwned(101L, 1L)).thenReturn(new AiConversation());
        var users = mock(LoginUserService.class);
        var user = new LoginUser(); user.setPermissionCodes(List.of("ai:assistant:use"));
        when(users.findByUserId(1L)).thenReturn(user);
        var locks = mock(ConversationExecutionLock.class);
        var lock = mock(RLock.class);
        when(locks.of(101)).thenReturn(lock);
        when(lock.tryLockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>(true));
        when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>((Void) null));
        var call = new AssistantMessage.ToolCall("ask1", "function", "ask_user", "{}");
        var response = org.springframework.ai.chat.messages.ToolResponseMessage.builder().responses(List.of(
                new org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse("ask1", "ask_user", "{\"status\":\"INTERRUPTED\"}"))).build();
        var checkpoint = mock(Checkpoint.class);
        when(checkpoint.getNextNodeId()).thenReturn("_AGENT_TOOL_");
        when(checkpoint.getState()).thenReturn(Map.of("messages", List.of(
                AssistantMessage.builder().toolCalls(List.of(call)).build(), response)));
        var saver = mock(RedisSaver.class);
        when(saver.get(any(RunnableConfig.class))).thenReturn(Optional.of(checkpoint));
        var agent = mock(ReactAgent.class);
        var end = endOutput();
        when(agent.stream(anyString(), any(RunnableConfig.class))).thenReturn(Flux.just(end));
        var service = new MainAgentService(agent, saver, conversations, users, locks, mock(com.qiheng.erp.ai.conversation.ConversationMessageStore.class), new com.fasterxml.jackson.databind.ObjectMapper());

        service.streamForUser(1, 101, "继续").blockLast();
        verify(agent).stream(eq("继续"), any(RunnableConfig.class));
        verify(agent, never()).getAndCompileGraph();
    }

    /** 模拟框架正常执行到结束节点。 */
    private static com.alibaba.cloud.ai.graph.NodeOutput endOutput() {
        var output = mock(com.alibaba.cloud.ai.graph.NodeOutput.class);
        when(output.isEND()).thenReturn(true);
        when(output.state()).thenReturn(new com.alibaba.cloud.ai.graph.OverAllState(Map.of()));
        return output;
    }
}
