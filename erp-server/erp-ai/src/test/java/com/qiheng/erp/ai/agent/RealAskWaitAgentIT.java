package com.qiheng.erp.ai.agent;

import com.qiheng.erp.ai.clarification.ClarificationTestAccess;

import com.qiheng.erp.ai.clarification.ClarificationService;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.MainAgentConfiguration;
import com.qiheng.erp.ai.config.OpenAiCompatibleChatModel;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.conversation.ConversationExecutionLock;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider;
import com.qiheng.erp.ai.clarification.AskUserTool.Question;
import com.qiheng.erp.ai.clarification.ClarificationAnswer;
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
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import reactor.core.publisher.Flux;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 模拟首次格式错误，验证真实模型修正后等待用户回答并继续。 */
@EnabledIfEnvironmentVariable(named = "ERP_REAL_QWEN_IT", matches = "true")
class RealAskWaitAgentIT {
    @Test
    void realModelRepairsAndContinuesAfterUserAnswer() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var settings = java.util.Objects.requireNonNull(yaml.getObject());
        String thinking = settings.getProperty("erp.ai.model.enable-thinking");
        Boolean enabled = thinking == null || thinking.isBlank() ? null : Boolean.valueOf(thinking.trim());
        var real = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.pro"),
                0.2, Duration.ofSeconds(90), enabled);
        var config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(config);
        try {
            var saver = RedisSaver.builder().redisson(redis)
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            var model = mock(ChatModel.class);
            var calls = new AtomicInteger();
            var feedback = new AtomicReference<String>();
            var received = new AtomicReference<String>();
            when(model.stream(any(Prompt.class))).thenAnswer(call -> {
                if (calls.getAndIncrement() == 0) {
                    var invalid = AssistantMessage.builder().toolCalls(List.of(
                            new AssistantMessage.ToolCall("invalid-call", "function", "ask_user",
                                    "{\"questions\":[{\"id\":\"\",\"question\":\"查询哪个仓库？\",\"type\":\"TEXT\",\"options\":[]}]}"))).build();
                    return Flux.just(new ChatResponse(List.of(new Generation(invalid))));
                }
                Prompt prompt = call.getArgument(0);
                prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast).flatMap(m -> m.getResponses().stream())
                        .forEach(response -> {
                            if (response.responseData().contains("追问格式错误")) feedback.set(response.responseData());
                            if (response.responseData().contains("真实测试仓库")) received.set(response.responseData());
                        });
                return real.stream(prompt);
            });
            long conversation = Math.abs(UUID.randomUUID().getLeastSignificantBits() / 2);
            var conversations = mock(AiConversationMapper.class);
            when(conversations.selectOwned(conversation, 1L)).thenReturn(new AiConversation());
            var users = mock(LoginUserService.class);
            var user = new LoginUser(); user.setPermissionCodes(List.of("ai:assistant:use"));
            when(users.findByUserId(1L)).thenReturn(user);
            var service = new ClarificationService(conversations, users, new ObjectMapper());
            var graph = mock(BusinessGraphProvider.class);
            when(graph.render()).thenReturn("【ERP业务参考】需要用户提供仓库名称。");
            var agent = new MainAgentConfiguration().mainAgent(model, saver,
                    new SqlQueryTool(mock(SqlGraphService.class)), new ReadDatasetTool(mock(DatasetManager.class)), graph, service);
            var main = new MainAgentService(agent, saver, conversations, users, new ConversationExecutionLock(redis), mock(com.qiheng.erp.ai.conversation.ConversationMessageStore.class), new com.fasterxml.jackson.databind.ObjectMapper());
            var eventCount = new AtomicInteger();
            var outputs = main.streamForUser(1L, conversation, "仓库名称尚未提供，请用追问工具询问；收到答案后只确认，不要查询。")
                    .doOnNext(event -> {
                        if (!"ask_user".equals(event.node())) return;
                        eventCount.incrementAndGet();
                        String id = (String) event.state().data().get("questionRequestId");
                        @SuppressWarnings("unchecked") var questions = (List<Question>) event.state().data().get("questions");
                        System.out.println("真实追问=" + questions);
                        ClarificationTestAccess.answer(service, 1L, conversation, id, questions.stream()
                                .map(q -> new ClarificationAnswer(q.id(), List.of(), "真实测试仓库")).toList(), "真实测试补充");
                    }).collectList().block(Duration.ofMinutes(3));
            assertThat(outputs).isNotEmpty();
            assertThat(eventCount.get()).isEqualTo(1);
            assertThat(feedback.get()).contains("追问格式错误");
            assertThat(received.get()).contains("真实测试仓库", "真实测试补充");
            System.out.println("模型收到的答案=" + received.get());
        } finally {
            redis.shutdown();
        }
    }
}
