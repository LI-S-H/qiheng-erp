package com.qiheng.erp.ai.agent;

import com.qiheng.erp.ai.clarification.ClarificationTestAccess;

import com.qiheng.erp.ai.clarification.ClarificationService;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.MainAgentConfiguration;
import com.qiheng.erp.ai.config.OpenAiCompatibleChatModel;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.conversation.ConversationExecutionLock;
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider;
import com.qiheng.erp.ai.clarification.ClarificationAnswer;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import reactor.core.publisher.Flux;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 真实数据库和模型验证完整消息流；仅操作唯一测试会话。 */
@EnabledIfEnvironmentVariable(named = "ERP_MESSAGE_MYSQL_IT", matches = "true")
class RealMessageFlowMysqlIT {
    @Test void persistsAfterSubscriberDisconnects() throws Exception {
        String mysqlUser = System.getProperty("ai.mysql.username", System.getenv("AI_MYSQL_IT_USERNAME"));
        String mysqlPassword = System.getProperty("ai.mysql.password", System.getenv("AI_MYSQL_IT_PASSWORD"));
        Assumptions.assumeTrue(mysqlUser != null && !mysqlUser.isBlank() && mysqlPassword != null,
                "需要提供 MySQL 测试账号与密码");
        var source = new DriverManagerDataSource(System.getProperty("ai.mysql.url", "jdbc:mysql://127.0.0.1:13307/erp?useSSL=false&allowPublicKeyRetrieval=true"),
                mysqlUser, mysqlPassword);
        var jdbc = new JdbcTemplate(source);
        long userId = 1900000000000000001L;
        long conversationId = Math.abs(UUID.randomUUID().getLeastSignificantBits() / 4) + 3000000000000000000L;
        jdbc.update("INSERT INTO ai_conversation(id,user_id,title,description) VALUES(?,?,?,?)", conversationId, userId, "消息流水测试", "自动测试");
        var redisConfig = new Config(); redisConfig.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(redisConfig);
        try {
            var configuration = new MybatisConfiguration(); configuration.addMapper(AiMessageMapper.class);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(source); factory.setConfiguration(configuration);
            var messageMapper = new SqlSessionTemplate(factory.getObject()).getMapper(AiMessageMapper.class);
            var proxy = new ProxyFactory(new ConversationMessageStore(messageMapper));
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
            var store = (ConversationMessageStore) proxy.getProxy();
            var saver = RedisSaver.builder().redisson(redis).stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            var yaml = new YamlPropertiesFactoryBean(); yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
            var p = java.util.Objects.requireNonNull(yaml.getObject());
            var real = OpenAiCompatibleChatModel.create(p.getProperty("erp.ai.api-key"), p.getProperty("erp.ai.base-url"), p.getProperty("erp.ai.model.pro"), 0.2, Duration.ofSeconds(90), null);
            var model = mock(ChatModel.class); var calls = new AtomicInteger();
            when(model.stream(any(Prompt.class))).thenAnswer(call -> {
                if (calls.getAndIncrement() == 0) return Flux.just(new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("请先确认仓库范围。 ").toolCalls(List.of(
                        new AssistantMessage.ToolCall("test-ask", "function", "ask_user", "{\"questions\":[{\"id\":\"w\",\"question\":\"仓库名称？\",\"type\":\"TEXT\",\"options\":[]}]}"))).build()))));
                return real.stream((Prompt) call.getArgument(0));
            });
            var conversations = mock(AiConversationMapper.class); when(conversations.selectOwned(conversationId, userId)).thenReturn(new AiConversation());
            var users = mock(LoginUserService.class); var user = new LoginUser(); user.setPermissionCodes(List.of("ai:assistant:use")); when(users.findByUserId(userId)).thenReturn(user);
            var mapper = new ObjectMapper(); var questions = new ClarificationService(conversations, users, mapper);
            var graph = mock(BusinessGraphProvider.class); when(graph.render()).thenReturn("ERP 仓库信息参考。");
            var agent = new MainAgentConfiguration().mainAgent(model, saver, new SqlQueryTool(mock(SqlGraphService.class)), new ReadDatasetTool(mock(DatasetManager.class)), graph, questions);
            var main = new MainAgentService(agent, saver, conversations, users, new ConversationExecutionLock(redis), store, mapper);
            var pending = new CompletableFuture<String>();
            var subscription = main.streamForUser(userId, conversationId, "询问仓库名称，收到答案后只确认。")
                    .subscribe(output -> { if ("ask_user".equals(output.node())) pending.complete((String) output.state().data().get("questionRequestId")); }, pending::completeExceptionally);
            String id = pending.get(30, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT run_status FROM ai_message WHERE conversation_id=? AND kind='USER_INPUT'", String.class, conversationId)).isEqualTo("WAITING_USER");
            assertThat(jdbc.queryForList("SELECT kind FROM ai_message WHERE conversation_id=? ORDER BY seq", String.class, conversationId)).containsSubsequence("ASSISTANT_STEP", "TOOL_CALL");
            subscription.dispose();
            ClarificationTestAccess.answer(questions, userId, conversationId, id, List.of(new ClarificationAnswer("w", List.of(), "测试仓库")), "本月");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            String status;
            do { status = jdbc.queryForObject("SELECT run_status FROM ai_message WHERE conversation_id=? AND kind='USER_INPUT'", String.class, conversationId); if (!"COMPLETED".equals(status)) Thread.sleep(100); } while (!"COMPLETED".equals(status) && System.nanoTime() < deadline);
            assertThat(status).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForList("SELECT kind FROM ai_message WHERE conversation_id=? ORDER BY seq", String.class, conversationId))
                    .containsSubsequence("USER_INPUT", "ASSISTANT_STEP", "TOOL_CALL", "TOOL_RESULT", "ASSISTANT_FINAL");
        } finally {
            redis.shutdown();
            jdbc.update("DELETE FROM ai_message WHERE conversation_id=? AND user_id=?", conversationId, userId);
            jdbc.update("DELETE FROM ai_conversation WHERE id=? AND user_id=? AND title=?", conversationId, userId, "消息流水测试");
        }
    }
}
