package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 通过可控模型观察真实 ReactAgent 的流式输出与 Redis 会话检查点。 */
@EnabledIfEnvironmentVariable(named = "ERP_SQL_GRAPH_REDIS_TEST", matches = "true")
class MainAgentStreamRedisTest {
    @Test
    void streamsAndPreservesIndependentConversationMessages() throws Exception {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(config);
        try {
            var saver = RedisSaver.builder().redisson(redis)
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            ChatModel model = mock(ChatModel.class);
            when(model.stream(any(Prompt.class))).thenAnswer(invocation -> Flux.just(
                    new ChatResponse(List.of(new Generation(new AssistantMessage("先查询库存，再判断缺货风险。"))))));
            var agent = ReactAgent.builder().name("main-test").model(model)
                    .systemPrompt("理解目标，说明步骤，必要时使用工具。")
                    .methodTools(new SqlQueryTool(mock(SqlGraphService.class)))
                    .saver(saver).releaseThread(false).build();
            String first = "main-test:" + UUID.randomUUID();
            String second = "main-test:" + UUID.randomUUID();
            var firstConfig = RunnableConfig.builder().threadId(first).build();
            var secondConfig = RunnableConfig.builder().threadId(second).build();
            var outputs = agent.stream("查询一号仓库库存", firstConfig).collectList().block(Duration.ofSeconds(15));
            assertThat(outputs).isNotEmpty();
            assertThat(outputs.stream().filter(StreamingOutput.class::isInstance)
                    .map(StreamingOutput.class::cast).map(StreamingOutput::chunk).toList())
                    .anyMatch(chunk -> chunk != null && chunk.contains("先查询库存"));
            agent.stream("继续查看库存", firstConfig).collectList().block(Duration.ofSeconds(15));
            agent.stream("查询二号仓库", secondConfig).collectList().block(Duration.ofSeconds(15));
            var firstCheckpoint = saver.get(firstConfig).orElseThrow().getState();
            var secondCheckpoint = saver.get(secondConfig).orElseThrow().getState();
            assertThat(firstCheckpoint.toString()).contains("查询一号仓库库存", "继续查看库存");
            assertThat(secondCheckpoint.toString()).contains("查询二号仓库").doesNotContain("一号仓库");
        } finally {
            redis.shutdown();
        }
    }
}
