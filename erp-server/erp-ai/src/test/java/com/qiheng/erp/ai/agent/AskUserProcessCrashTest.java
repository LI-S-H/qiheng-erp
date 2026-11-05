package com.qiheng.erp.ai.agent;

import com.qiheng.erp.ai.clarification.ClarificationService;

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
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 强杀独立 JVM，验证未完成工具调用后接收新消息。 */
@EnabledIfEnvironmentVariable(named = "ERP_SQL_GRAPH_REDIS_TEST", matches = "true")
class AskUserProcessCrashTest {
    @Test void continuesWithNewMessageAfterProcessCrash() throws Exception {
        long conversation = Math.abs(UUID.randomUUID().getLeastSignificantBits() / 2);
        crashAtQuestion(conversation, "first");
        run(conversation, "resume");
    }

    private void crashAtQuestion(long conversation, String phase) throws Exception {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        var options = java.nio.file.Files.createTempFile(java.nio.file.Path.of("target"), "crash-classpath-", ".args");
        java.nio.file.Files.writeString(options, "-cp\n\"" + System.getProperty("surefire.test.class.path").replace("\\", "\\\\") + "\"\n");
        Process process = new ProcessBuilder(javaBin, "@" + options.toAbsolutePath(),
                AskUserProcessCrashTest.class.getName(), String.valueOf(conversation), phase)
                .redirectErrorStream(true).start();
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line; boolean ready = false;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("CRASH_READY")) { ready = true; break; }
            }
            assertThat(ready).as("进程进入追问等待: " + phase).isTrue();
        } finally {
            process.destroyForcibly();
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
            java.nio.file.Files.deleteIfExists(options);
        }
        // 模拟进程退出后，等待 Redisson 看门狗持有的会话锁自然过期。
        var config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(config);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
            var lock = new ConversationExecutionLock(redis).of(conversation);
            while (lock.isLocked() && System.nanoTime() < deadline) Thread.sleep(200);
            assertThat(lock.isLocked()).isFalse();
        } finally { redis.shutdown(); }
    }

    public static void main(String[] args) throws Exception { run(Long.parseLong(args[0]), args[1]); }

    private static void run(long conversation, String phase) throws Exception {
        var config = new Config(); config.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(config);
        try {
            var saver = RedisSaver.builder().redisson(redis).stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            var conversations = mock(AiConversationMapper.class); when(conversations.selectOwned(conversation, 1L)).thenReturn(new AiConversation());
            var users = mock(LoginUserService.class); var user = new LoginUser(); user.setPermissionCodes(List.of("ai:assistant:use")); when(users.findByUserId(1L)).thenReturn(user);
            var questions = new ClarificationService(conversations, users, new ObjectMapper(), Duration.ofSeconds(30));
            var model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
                Prompt prompt = invocation.getArgument(0);
                boolean continuing = prompt.getInstructions().stream().filter(UserMessage.class::isInstance)
                        .map(UserMessage.class::cast).anyMatch(m -> "再分析销售".equals(m.getText()));
                if (continuing) {
                    assertThat(prompt.getInstructions().toString()).contains("ask_user", "再分析销售");
                    assertThat(prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
                            .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream()))
                            .anySatisfy(response -> {
                                assertThat(response.id()).isEqualTo("ask-crash");
                                assertThat(response.responseData()).contains("INTERRUPTED");
                            });
                }
                var message = continuing ? new AssistantMessage("继续分析销售") : AssistantMessage.builder().toolCalls(List.of(
                        new AssistantMessage.ToolCall("ask-crash", "function", "ask_user", "{\"questions\":[{\"id\":\"w\",\"question\":\"仓库？\",\"type\":\"TEXT\",\"options\":[]}]}"))).build();
                return Flux.just(new ChatResponse(List.of(new Generation(message))));
            });
            var business = mock(BusinessGraphProvider.class); when(business.render()).thenReturn("业务图");
            var agent = new MainAgentConfiguration().mainAgent(model, saver, new SqlQueryTool(mock(SqlGraphService.class)),
                    new ReadDatasetTool(mock(DatasetManager.class)), business, questions);
            var main = new MainAgentService(agent, saver, conversations, users, new ConversationExecutionLock(redis), mock(com.qiheng.erp.ai.conversation.ConversationMessageStore.class), new com.fasterxml.jackson.databind.ObjectMapper());
            String input = "first".equals(phase) ? "查询仓库" : "再分析销售";
            main.streamForUser(1, conversation, input).doOnNext(event -> {
                if (!"ask_user".equals(event.node())) return;
                System.out.println("CRASH_READY " + phase);
                System.out.flush();
            }).blockLast(Duration.ofSeconds(35));
            if ("resume".equals(phase)) {
                var checkpoint = saver.get(RunnableConfig.builder().threadId("main:1:" + conversation).build()).orElseThrow();
                var messages = (List<?>) checkpoint.getState().get("messages");
                assertThat(messages.stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                        .filter(m -> "再分析销售".equals(m.getText())).count()).isEqualTo(1);
                System.out.println("CRASH_RECOVERED: 中断结果已配对，新消息出现一次");
            }
        } finally { redis.shutdown(); }
    }
}
