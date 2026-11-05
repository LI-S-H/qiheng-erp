package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.clarification.ClarificationService;
import com.qiheng.erp.ai.config.MainAgentConfiguration;
import com.qiheng.erp.ai.conversation.ConversationExecutionLock;
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.ai.sql.tool.SqlToolContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.messages.Message;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用独立会话验证真实 Redis 检查点中的未完成 SQL 调用可以继续。 */
@EnabledIfEnvironmentVariable(named = "ERP_REDIS_IT", matches = "true")
class InterruptedSqlRecoveryIT {
    @Test
    void interruptedSqlCallGetsPairedBeforeNewUserMessage() throws Exception {
        var config = new Config(); config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        var redis = Redisson.create(config);
        long conversation = Math.abs(UUID.randomUUID().getLeastSignificantBits() / 2);
        String thread = "main:1:" + conversation;
        try {
            var saver = RedisSaver.builder().redisson(redis).stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            var conversations = mock(AiConversationMapper.class); when(conversations.selectOwned(conversation, 1L)).thenReturn(new AiConversation());
            var users = mock(LoginUserService.class); var user = new LoginUser(); user.setPermissionCodes(List.of("ai:assistant:use")); when(users.findByUserId(1L)).thenReturn(user);
            var graph = mock(BusinessGraphProvider.class); when(graph.render()).thenReturn("ERP仓库");
            var sqlService = mock(SqlGraphService.class);
            when(sqlService.startForUser(anyLong(), anyLong(), anyString(), anyMap())).thenThrow(new IllegalStateException("模拟工具故障"));
            var sql = new SqlQueryTool(sqlService); var dataset = new ReadDatasetTool(mock(DatasetManager.class));
            var factory = new MainAgentConfiguration();
            var call = new AssistantMessage.ToolCall("old-sql", "function", "query_sql_data", "{\"requirement\":{\"goal\":\"库存\",\"dataRequirement\":\"FULL\"}}");
            var clarification = new ClarificationService(conversations, users, new ObjectMapper());
            var oldConfig = RunnableConfig.builder().threadId(thread).build();
            var oldCheckpoint = com.alibaba.cloud.ai.graph.checkpoint.Checkpoint.builder()
                    .id(UUID.randomUUID().toString()).nodeId("model").nextNodeId("_AGENT_TOOL_")
                    .state(java.util.Map.of("messages", List.of(new UserMessage("库存"), AssistantMessage.builder().toolCalls(List.of(call)).build())))
                    .build();
            saver.put(oldConfig, oldCheckpoint);
            var checkpoint = saver.get(RunnableConfig.builder().threadId(thread).build()).orElseThrow();
            assertThat(checkpoint.getNextNodeId()).isEqualTo("_AGENT_TOOL_");
            var nextModel = mock(ChatModel.class); var paired = new AtomicBoolean();
            var received = new AtomicReference<List<Message>>();
            when(nextModel.stream(any(Prompt.class))).thenAnswer(invocation -> {
                Prompt prompt = invocation.getArgument(0);
                received.set(List.copyOf(prompt.getInstructions()));
                boolean result = prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                        .flatMap(message -> message.getResponses().stream()).anyMatch(item -> item.id().equals("old-sql") && item.responseData().contains("INTERRUPTED"));
                boolean input = prompt.getInstructions().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast).anyMatch(message -> message.getText().equals("继续"));
                paired.set(result && input);
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("继续处理")))));
            });
            var next = factory.mainAgent(nextModel, saver, sql, dataset, graph, clarification);
            var main = new MainAgentService(next, saver, conversations, users, new ConversationExecutionLock(redis), mock(ConversationMessageStore.class), new ObjectMapper());
            assertThat(main.streamForUser(1L, conversation, "继续").collectList().block(Duration.ofSeconds(30))).isNotEmpty();
            assertThat(paired).isTrue();
            int responseIndex = -1; int userIndex = -1;
            for (int i = 0; i < received.get().size(); i++) {
                var message = received.get().get(i);
                if (message instanceof ToolResponseMessage response && response.getResponses().stream().anyMatch(item -> item.id().equals("old-sql"))) {
                    responseIndex = i;
                }
                if (message instanceof UserMessage userMessage && userMessage.getText().equals("继续")) {
                    userIndex = i;
                }
            }
            assertThat(responseIndex).isGreaterThanOrEqualTo(0).isLessThan(userIndex);
            verifyNoInteractions(sqlService);
        } finally {
            // 仅删除本次随机会话的测试检查点。
            redis.getKeys().deleteByPattern("*" + thread + "*");
            redis.shutdown();
        }
    }
}
