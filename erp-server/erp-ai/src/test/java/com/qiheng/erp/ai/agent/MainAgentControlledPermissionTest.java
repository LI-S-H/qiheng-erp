package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.conversation.ConversationExecutionLock;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import org.junit.jupiter.api.Test;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.redisson.api.RLock;
import org.redisson.misc.CompletableFutureWrapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 可控模型下验证正式入口会话隔离及工具身份传递。 */
class MainAgentControlledPermissionTest {
    @Test
    void rejectsOtherUsersBeforeModelAndToolAndRunsOwnedSessionWithTrustedIdentity() throws Exception {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            boolean answered = prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
            var response = answered ? new AssistantMessage("查询已完成。")
                    : AssistantMessage.builder().content("先查询库存。")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("controlled-1", "function", "query_sql_data",
                            "{\"requirement\":{\"goal\":\"查询库存\",\"dataRequirement\":\"FULL\"}}"))).build();
            return Flux.just(new ChatResponse(List.of(new Generation(response))));
        });
        var sqlService = mock(SqlGraphService.class);
        when(sqlService.startForUserAtDate(eq(1L), eq(101L), anyString(), anyMap(), anyString()))
                .thenReturn(Map.of("status", "FAILED", "message", "测试返回"));
        var datasets = mock(DatasetManager.class);
        var agent = ReactAgent.builder().name("controlled-permission").model(model)
                .methodTools(new SqlQueryTool(sqlService), new ReadDatasetTool(datasets))
                .interceptors(new TrustedToolContextInterceptor()).build();
        var conversations = mock(AiConversationMapper.class);
        when(conversations.selectOwned(101L, 1L)).thenReturn(new AiConversation());
        var users = mock(LoginUserService.class);
        var user = new LoginUser();
        user.setPermissionCodes(List.of("ai:assistant:use"));
        when(users.findByUserId(1L)).thenReturn(user);
        var locks = mock(ConversationExecutionLock.class);
        var lock = mock(RLock.class);
        when(locks.of(anyLong())).thenReturn(lock);
        when(lock.tryLockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>(true));
        when(lock.unlockAsync(anyLong())).thenReturn(new CompletableFutureWrapper<>((Void) null));
        var service = new MainAgentService(agent, mock(com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver.class), conversations, users, locks, mock(com.qiheng.erp.ai.conversation.ConversationMessageStore.class), new com.fasterxml.jackson.databind.ObjectMapper());

        assertThatThrownBy(() -> service.streamForUser(2L, 101L, "查询库存").blockLast(Duration.ofSeconds(15)))
                .hasMessageContaining("无权访问");
        assertThatThrownBy(() -> service.streamForUser(1L, 202L, "查询库存").blockLast(Duration.ofSeconds(15)))
                .hasMessageContaining("无权访问");
        user.setPermissionCodes(List.of());
        assertThatThrownBy(() -> service.streamForUser(1L, 101L, "查询库存").blockLast(Duration.ofSeconds(15)))
                .hasMessageContaining("无 AI 使用权限");
        verify(model, never()).stream(any(Prompt.class));
        verifyNoInteractions(sqlService, datasets);

        user.setPermissionCodes(List.of("ai:assistant:use"));
        assertThat(service.streamForUser(1L, 101L, "查询库存")
                .collectList().block(Duration.ofSeconds(15))).isNotEmpty();
        verify(sqlService).startForUserAtDate(eq(1L), eq(101L), anyString(), anyMap(), anyString());
        verifyNoInteractions(datasets);
        verify(lock, times(4)).unlockAsync(anyLong());
    }
}
