package com.qiheng.erp.ai.experience;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.qiheng.erp.ai.config.properties.AiExperienceProperties;
import com.qiheng.erp.ai.mapper.AiExperienceSummaryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 经验目录进入真实 ReactAgent 请求的集成验证。
 *
 * <p>用 MemorySaver 和可控模型跑一轮，确认 hook 在图中执行、
 * 目录通过状态写回进入模型输入。</p>
 *
 * @author Li
 * @since 2026-11-20
 */
class ExperienceInjectionIntegrationTest {

    @Test
    void catalogReachesTheRealModelRequest() throws Exception {
        var summaryMapper = mock(AiExperienceSummaryMapper.class);
        when(summaryMapper.selectSummary("MAIN")).thenReturn("SQL 取数注意列名、单位");
        var properties = new AiExperienceProperties(true, "0 10 3 * * ?", 100, 7, 6,
                600_000, 4000, 5, 400, 100, 400, 10, 60, "flash");
        var hook = new ExperienceInjectionHook(new ExperienceProvider(summaryMapper, properties), "MAIN");

        var model = mock(ChatModel.class);
        var requests = new ArrayList<List<Message>>();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            requests.add(List.copyOf(prompt.getInstructions()));
            return Flux.just(new ChatResponse(
                    List.of(new Generation(new AssistantMessage("收到")))));
        });
        var agent = ReactAgent.builder().name("experience-integration").model(model)
                .systemPrompt("测试").hooks(hook).saver(new MemorySaver()).releaseThread(false).build();

        agent.stream("分析库存", RunnableConfig.builder()
                        .threadId("experience-integration").build())
                .collectList().block(Duration.ofSeconds(10));

        assertThat(requests).isNotEmpty();
        assertThat(requests.getFirst()).anyMatch(message -> message instanceof UserMessage user
                && user.getText().startsWith("【经验目录】")
                && user.getText().contains("SQL 取数注意列名"));
        assertThat(requests.getFirst()).anyMatch(message -> message instanceof UserMessage user
                && user.getText().equals("分析库存"));
    }
}