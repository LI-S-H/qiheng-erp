package com.qiheng.erp.ai.agent;

import com.qiheng.erp.ai.clarification.ClarificationService;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.MainAgentConfiguration;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.conversation.ConversationExecutionLock;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider;
import com.qiheng.erp.ai.clarification.ClarificationAnswer;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 使用独立服务实例恢复旧检查点，模拟原等待线程消失。 */
@EnabledIfEnvironmentVariable(named = "ERP_SQL_GRAPH_REDIS_TEST", matches = "true")
class AskUserCheckpointProbeTest {
    @Test void resumeToolFromStoredCheckpoint() throws Exception {
        var config = new Config(); config.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(config);
        try {
            var saver = RedisSaver.builder().redisson(redis).stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            var conversations = mock(AiConversationMapper.class);
            long conversation = Math.abs(UUID.randomUUID().getLeastSignificantBits() / 2);
            when(conversations.selectOwned(conversation, 1L)).thenReturn(new AiConversation());
            var users = mock(LoginUserService.class); var user = new LoginUser();
            user.setPermissionCodes(List.of("ai:assistant:use")); when(users.findByUserId(1L)).thenReturn(user);
            var model = mock(ChatModel.class); var seen = new AtomicReference<Prompt>();
            when(model.stream(any(Prompt.class))).thenAnswer(c -> {
                Prompt prompt = c.getArgument(0);
                if (prompt.getInstructions().stream().filter(org.springframework.ai.chat.messages.UserMessage.class::isInstance)
                        .map(org.springframework.ai.chat.messages.UserMessage.class::cast)
                        .anyMatch(message -> "查询一号仓库".equals(message.getText()))) {
                    seen.set(prompt);
                    return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("继续查询一号仓库")))));
                }
                var call = new AssistantMessage.ToolCall("ask-1", "function", "ask_user", "{\"questions\":[{\"id\":\"w\",\"question\":\"仓库？\",\"type\":\"TEXT\",\"options\":[]}]}");
                return Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(call)).build()))));
            });
            var graph = mock(BusinessGraphProvider.class); when(graph.render()).thenReturn("业务图");
            var oldService = new ClarificationService(conversations, users, new ObjectMapper(), Duration.ofMillis(250));
            var factory = new MainAgentConfiguration();
            var sql = new SqlQueryTool(mock(SqlGraphService.class)); var data = new ReadDatasetTool(mock(DatasetManager.class));
            var oldAgent = factory.mainAgent(model, saver, sql, data, graph, oldService);
            var thread = "main:1:" + conversation; var cp = new AtomicReference<String>();
            var oldRequest = new AtomicReference<String>();
            Consumer<NodeOutput> oldEvents = e -> { if ("ask_user".equals(e.node())) { oldRequest.set((String) e.state().data().get("questionRequestId")); cp.set(saver.get(RunnableConfig.builder().threadId(thread).build()).orElseThrow().getId()); } };
            var original = RunnableConfig.builder().threadId(thread)
                    .addMetadata(TrustedToolContextInterceptor.TRUSTED_CONTEXT, new SqlToolContext(1L, conversation))
                    .addMetadata(ClarificationService.EVENT_KEY, oldEvents).build();
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> oldAgent.stream("仓库", original).collectList().block(Duration.ofSeconds(10)))
                    .hasMessageContaining("等待用户回答超时");
            assertThat(cp.get()).isNotBlank();
            var newService = new ClarificationService(conversations, users, new ObjectMapper(), Duration.ofSeconds(3));
            var newAgent = factory.mainAgent(model, saver, sql, data, graph, newService);
            var main = new MainAgentService(newAgent, saver, conversations, users, new ConversationExecutionLock(redis), mock(com.qiheng.erp.ai.conversation.ConversationMessageStore.class), new com.fasterxml.jackson.databind.ObjectMapper());
            var output = main.streamForUser(1L, conversation, "查询一号仓库").collectList().block(Duration.ofSeconds(10));
            assertThat(output).isNotEmpty();
            assertThat(seen.get()).isNotNull();
            assertThat(seen.get().getInstructions().toString()).contains("ask_user", "查询一号仓库");
            assertThat(seen.get().getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                    .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream()))
                    .anySatisfy(response -> {
                        assertThat(response.id()).isEqualTo("ask-1");
                        assertThat(response.responseData()).contains("INTERRUPTED");
                    });
            var messages = (List<?>) saver.get(RunnableConfig.builder().threadId(thread).build()).orElseThrow().getState().get("messages");
            assertThat(messages.toString()).contains("查询一号仓库");
        } finally { redis.shutdown(); }
    }
}
