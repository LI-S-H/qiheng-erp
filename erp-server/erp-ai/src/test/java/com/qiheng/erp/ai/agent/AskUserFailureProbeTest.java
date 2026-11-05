package com.qiheng.erp.ai.agent;

import com.qiheng.erp.ai.clarification.ClarificationService;

import com.alibaba.cloud.ai.graph.OverAllState;
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
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 观察框架对超时和参数解析错误的实际处理。 */
@EnabledIfEnvironmentVariable(named = "ERP_SQL_GRAPH_REDIS_TEST", matches = "true")
class AskUserFailureProbeTest {
    @Test void timeout() throws Exception {
        probe("timeout", "{\"questions\":[{\"id\":\"w\",\"question\":\"仓库？\",\"type\":\"TEXT\",\"options\":[]}]}");
    }
    @Test void malformedJson() throws Exception { probe("malformed", "{bad-json"); }
    @Test void invalidEnum() throws Exception {
        probe("enum", "{\"questions\":[{\"id\":\"w\",\"question\":\"仓库？\",\"type\":\"INVALID\",\"options\":[]}]}");
    }

    private void probe(String label, String args) throws Exception {
        var redisConfig = new Config(); redisConfig.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(redisConfig);
        try {
            var saver = RedisSaver.builder().redisson(redis).stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            var model = mock(ChatModel.class);
            var count = new AtomicInteger();
            var feedback = new AtomicReference<String>();
            when(model.stream(any(Prompt.class))).thenAnswer(call -> {
                Prompt prompt = call.getArgument(0);
                if (count.getAndIncrement() == 0) {
                    var msg = AssistantMessage.builder().toolCalls(List.of(new AssistantMessage.ToolCall("call1", "function", "ask_user", args))).build();
                    return Flux.just(new ChatResponse(List.of(new Generation(msg))));
                }
                prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                        .flatMap(m -> m.getResponses().stream()).forEach(r -> feedback.set(r.responseData()));
                return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("已处理")))));
            });
            long conversation = Math.abs(UUID.randomUUID().getLeastSignificantBits() / 2);
            var conversations = mock(AiConversationMapper.class); when(conversations.selectOwned(conversation, 1L)).thenReturn(new AiConversation());
            var users = mock(LoginUserService.class); var user = new LoginUser(); user.setPermissionCodes(List.of("ai:assistant:use")); when(users.findByUserId(1L)).thenReturn(user);
            var service = new ClarificationService(conversations, users, new ObjectMapper(), Duration.ofMillis(80));
            var graph = mock(BusinessGraphProvider.class); when(graph.render()).thenReturn("业务图");
            var agent = new MainAgentConfiguration().mainAgent(model, saver, new SqlQueryTool(mock(SqlGraphService.class)), new ReadDatasetTool(mock(DatasetManager.class)), graph, service);
            var locks = new ConversationExecutionLock(redis);
            var main = new MainAgentService(agent, saver, conversations, users, locks, mock(com.qiheng.erp.ai.conversation.ConversationMessageStore.class), new com.fasterxml.jackson.databind.ObjectMapper());
            Throwable error = null;
            try { main.streamForUser(1, conversation, "测试").collectList().block(Duration.ofSeconds(15)); }
            catch (Throwable e) { error = e; }
            System.out.println("PROBE " + label + " calls=" + count + " feedback=" + feedback + " error=" + error + " lock=" + locks.of(conversation).isLocked());
            assertThat(locks.of(conversation).isLocked()).isFalse();
            if (label.equals("timeout")) {
                assertThat(count.get()).isEqualTo(1);
                assertThat(error).isNotNull().hasMessageContaining("等待用户回答超时");
                assertThat(feedback.get()).isNull();
            } else {
                assertThat(error).isNull();
                assertThat(count.get()).isEqualTo(2);
                assertThat(feedback.get()).contains("追问格式错误");
            }
        } finally { redis.shutdown(); }
    }
}
