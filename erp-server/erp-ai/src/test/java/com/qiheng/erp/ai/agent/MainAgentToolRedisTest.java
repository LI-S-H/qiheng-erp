package com.qiheng.erp.ai.agent;

import com.qiheng.erp.ai.clarification.ClarificationService;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.qiheng.erp.ai.config.MainAgentConfiguration;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.conversation.ConversationExecutionLock;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.ai.sql.tool.SqlToolContext;
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
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import reactor.core.scheduler.Schedulers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 观察真实 Agent 工具调用后的 Redis 消息与流输出，SQL 服务使用可控替身。 */
@EnabledIfEnvironmentVariable(named = "ERP_SQL_GRAPH_REDIS_TEST", matches = "true")
class MainAgentToolRedisTest {
    /** 取消外部订阅不能释放仍在运行的内部流所持会话锁。 */
    @Test
    void cancelledSubscriberKeepsLockUntilInternalRunFinishes() throws Exception {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(config);
        try {
            var agent = mock(ReactAgent.class);
            var gate = Sinks.<Void>empty();
            var entered = new CountDownLatch(1);
            when(agent.stream(anyString(), any(RunnableConfig.class))).thenReturn(Flux.defer(() -> {
                entered.countDown();
                return gate.asMono().thenMany(Flux.empty());
            }));
            var conversations = mock(AiConversationMapper.class);
            long conversation = Math.abs(UUID.randomUUID().getLeastSignificantBits() / 2);
            when(conversations.selectOwned(conversation, 1L)).thenReturn(new AiConversation());
            var users = mock(LoginUserService.class);
            var user = new LoginUser(); user.setPermissionCodes(List.of("ai:assistant:use"));
            when(users.findByUserId(1L)).thenReturn(user);
            var service = new MainAgentService(agent, mock(RedisSaver.class), conversations, users, new ConversationExecutionLock(redis), mock(com.qiheng.erp.ai.conversation.ConversationMessageStore.class), new com.fasterxml.jackson.databind.ObjectMapper());
            var first = service.streamForUser(1, conversation, "查询").subscribe();
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            first.dispose();
            assertThatThrownBy(() -> service.streamForUser(1, conversation, "再次查询").blockLast(Duration.ofSeconds(5)))
                    .hasMessage("会话正在执行其他任务");
            gate.tryEmitEmpty();
            var lock = new ConversationExecutionLock(redis).of(conversation);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (lock.isLocked() && System.nanoTime() < deadline) Thread.sleep(20);
            assertThat(lock.isLocked()).isFalse();
            service.streamForUser(1, conversation, "第三次查询").blockLast(Duration.ofSeconds(5));
        } finally {
            redis.shutdown();
        }
    }
    /** 正式服务使用真实 ReactAgent 和 Redis，验证工具身份与会话隔离。 */
    @Test
    void officialServiceStreamsToolCallsAndIsolatesConversations() throws Exception {
        Config redisConfig = new Config();
        redisConfig.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(redisConfig);
        try {
            var saver = RedisSaver.builder().redisson(redis)
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            var sql = mock(SqlGraphService.class);
            when(sql.startForUser(anyLong(), anyLong(), anyString(), anyMap()))
                    .thenReturn(Map.of("status", "SUCCESS", "datasetId", "ds-example"));
            ChatModel model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenAnswer(call -> {
                Prompt prompt = call.getArgument(0);
                boolean done = prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
                var message = done ? new AssistantMessage("已取得数据。")
                        : AssistantMessage.builder().content("先查询库存。")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "query_sql_data",
                                "{\"requirement\":{\"goal\":\"库存\",\"dataRequirement\":\"FULL\"}}"))).build();
                return Flux.just(new ChatResponse(List.of(new Generation(message))));
            });
            var agent = new MainAgentConfiguration().mainAgent(model, saver,
                    new SqlQueryTool(sql), new ReadDatasetTool(mock(DatasetManager.class)),
                    mock(com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider.class), mock(ClarificationService.class));
            var conversations = mock(AiConversationMapper.class);
            long conversation = Math.abs(UUID.randomUUID().getLeastSignificantBits() / 2);
            when(conversations.selectOwned(conversation, 1L)).thenReturn(new AiConversation());
            var users = mock(LoginUserService.class);
            var user = new LoginUser(); user.setPermissionCodes(List.of("ai:assistant:use"));
            when(users.findByUserId(1L)).thenReturn(user);
            var service = new MainAgentService(agent, saver, conversations, users, new ConversationExecutionLock(redis), mock(com.qiheng.erp.ai.conversation.ConversationMessageStore.class), new com.fasterxml.jackson.databind.ObjectMapper());
            var outputs = service.streamForUser(1, conversation, "查询库存").collectList().block(Duration.ofSeconds(15));
            assertThat(outputs).isNotEmpty();
            verify(sql).startForUser(eq(1L), eq(conversation), anyString(), anyMap());
            var own = RunnableConfig.builder().threadId("main:1:" + conversation).build();
            var other = RunnableConfig.builder().threadId("main:2:" + conversation).build();
            assertThat(saver.get(own)).isPresent();
            assertThat(saver.get(other)).isEmpty();
            assertThatThrownBy(() -> service.streamForUser(2, conversation, "查询库存").blockLast())
                    .hasMessage("会话不存在或无权访问");
        } finally {
            redis.shutdown();
        }
    }
    /** 工具阻塞时取消订阅，检查 Redis 保存的位置；不声称取消中止了工具。 */
    @Test
    void observesCheckpointWhenToolIsBlockedAndSubscriberCancels() throws Exception {
        Config redisConfig = new Config();
        redisConfig.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(redisConfig);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        try {
            var saver = RedisSaver.builder().redisson(redis)
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            SqlGraphService service = mock(SqlGraphService.class);
            when(service.startForUser(anyLong(), anyLong(), anyString(), anyMap())).thenAnswer(call -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                finally { exited.countDown(); }
                return Map.of("status", "SUCCESS", "datasetId", "ds-example");
            });
            ChatModel model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenReturn(Flux.just(new ChatResponse(List.of(
                    new Generation(AssistantMessage.builder().content("查询库存")
                            .toolCalls(List.of(new AssistantMessage.ToolCall("call-stop", "function", "query_sql_data",
                                    "{\"requirement\":{\"goal\":\"库存\",\"dataRequirement\":\"FULL\"}}"))).build())))));
            ToolInterceptor identity = new ToolInterceptor() {
                @Override public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
                    return handler.call(ToolCallRequest.builder(request)
                            .context(Map.of(SqlQueryTool.CONTEXT_KEY, new SqlToolContext(1, 101))).build());
                }
                @Override public String getName() { return "test_identity"; }
            };
            var agent = ReactAgent.builder().name("tool-cancel-test").model(model)
                    .methodTools(new SqlQueryTool(service)).interceptors(identity).saver(saver).releaseThread(false).build();
            var config = RunnableConfig.builder().threadId("cancel-test:" + UUID.randomUUID()).build();
            var subscription = agent.stream("库存", config).subscribeOn(Schedulers.boundedElastic()).subscribe();
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var checkpoint = saver.get(config).orElseThrow();
            assertThat(checkpoint.getState().get("messages").toString()).contains("call-stop", "query_sql_data");
            System.out.println("工具执行期间检查点下一节点: " + checkpoint.getNextNodeId());
            subscription.dispose();
            assertThat(exited.await(200, TimeUnit.MILLISECONDS)).isFalse();
        } finally {
            release.countDown();
            exited.await(10, TimeUnit.SECONDS);
            redis.shutdown();
        }
    }
    @Test
    void storesToolCallAndResponseInCheckpoint() throws Exception {
        Config redisConfig = new Config();
        redisConfig.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(redisConfig);
        try {
            var saver = RedisSaver.builder().redisson(redis)
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            SqlGraphService service = mock(SqlGraphService.class);
            when(service.startForUser(anyLong(), anyLong(), anyString(), anyMap()))
                    .thenReturn(Map.of("status", "SUCCESS", "datasetId", "ds-example"));
            ChatModel model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenAnswer(call -> {
                Prompt prompt = call.getArgument(0);
                boolean done = prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
                AssistantMessage message = done ? new AssistantMessage("已取得数据。")
                        : AssistantMessage.builder().content("先查询库存，确认商品数量。")
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "query_sql_data",
                                "{\"requirement\":{\"goal\":\"库存\",\"dataRequirement\":\"FULL\"}}"))).build();
                return Flux.just(new ChatResponse(List.of(new Generation(message))));
            });
            ToolInterceptor identity = new ToolInterceptor() {
                @Override public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
                    var trusted = request.getExecutionContext().orElseThrow().config().metadata("trustedSqlContext").orElseThrow();
                    return handler.call(ToolCallRequest.builder(request)
                            .context(Map.of(SqlQueryTool.CONTEXT_KEY, trusted)).build());
                }
                @Override public String getName() { return "test_identity"; }
            };
            var agent = ReactAgent.builder().name("tool-redis-test").model(model)
                    .methodTools(new SqlQueryTool(service)).interceptors(identity).saver(saver).releaseThread(false).build();
            var config = RunnableConfig.builder().threadId("tool-redis:" + UUID.randomUUID())
                    .addMetadata("trustedSqlContext", new SqlToolContext(1, 101)).build();
            var outputs = agent.stream("库存", config).collectList().block(Duration.ofSeconds(15));
            assertThat(outputs.stream().filter(StreamingOutput.class::isInstance)
                    .map(StreamingOutput.class::cast).map(StreamingOutput::chunk).toList())
                    .anyMatch(chunk -> chunk != null && chunk.contains("已取得数据"));
            var checkpoint = saver.get(config).orElseThrow();
            var messages = (List<?>) checkpoint.getState().get("messages");
            assertThat(messages).anySatisfy(message -> {
                assertThat(message).isInstanceOf(AssistantMessage.class);
                assertThat(((AssistantMessage) message).getToolCalls()).anyMatch(tool -> tool.id().equals("call-1"));
            });
            assertThat(messages).anySatisfy(message -> {
                assertThat(message).isInstanceOf(ToolResponseMessage.class);
                assertThat(((ToolResponseMessage) message).getResponses()).anyMatch(response ->
                        response.id().equals("call-1") && response.responseData().contains("ds-example"));
            });
        } finally {
            redis.shutdown();
        }
    }
}
