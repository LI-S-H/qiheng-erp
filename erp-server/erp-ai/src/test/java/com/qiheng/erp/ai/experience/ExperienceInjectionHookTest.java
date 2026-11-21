package com.qiheng.erp.ai.experience;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.qiheng.erp.ai.agent.context.CompressionTranscript;
import com.qiheng.erp.ai.config.properties.AiExperienceProperties;
import com.qiheng.erp.ai.mapper.AiExperienceSummaryMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 经验目录注入契约：有摘要才注入，同一份目录不重复写回，旧目录会被替换。
 *
 * @author Li
 * @since 2026-11-20
 */
class ExperienceInjectionHookTest {

    @Test
    void injectsSummaryAtTheFront() throws Exception {
        var hook = hook("SQL 取数注意列名、单位");

        List<Message> result = messagesOf(hook.beforeModel(List.of(new UserMessage("分析库存")), config()));

        assertThat(result).hasSize(2);
        assertThat(result.getFirst().getText()).startsWith(CompressionTranscript.EXPERIENCE_PREFIX)
                .contains("SQL 取数注意列名、单位")
                .contains("searchExperience");
        assertThat(result.get(1).getText()).isEqualTo("分析库存");
    }

    @Test
    void noSummaryLeavesMessagesUntouched() throws Exception {
        var hook = hook(null);

        AgentCommand command = hook.beforeModel(List.of(new UserMessage("分析库存")), config());

        assertThat(messagesOf(command)).isNull();
    }

    @Test
    void sameSummaryAtFrontIsNotWrittenAgain() throws Exception {
        var hook = hook("SQL 取数注意列名、单位");
        List<Message> first = messagesOf(hook.beforeModel(
                List.of(new UserMessage("分析库存")), config()));

        AgentCommand second = hook.beforeModel(first, config());

        assertThat(messagesOf(second)).isNull();
    }

    @Test
    void oldSummaryInTheMiddleIsReplaced() throws Exception {
        var hook = hook("新的目录摘要");
        var stale = new UserMessage(CompressionTranscript.EXPERIENCE_PREFIX + "\n旧摘要");
        var messages = List.<Message>of(new UserMessage("分析库存"), stale,
                new AssistantMessage("好的"));

        List<Message> result = messagesOf(hook.beforeModel(messages, config()));

        assertThat(result).hasSize(3);
        assertThat(result.getFirst().getText()).contains("新的目录摘要");
        assertThat(result.stream().filter(UserMessage.class::isInstance)
                .map(Message::getText).filter(text -> text.startsWith(CompressionTranscript.EXPERIENCE_PREFIX)))
                .hasSize(1);
    }

    private static ExperienceInjectionHook hook(String summary) {
        var mapper = mock(AiExperienceSummaryMapper.class);
        when(mapper.selectSummary("MAIN")).thenReturn(summary);
        var properties = new AiExperienceProperties(true, "0 10 3 * * ?", 100, 7, 6,
                600_000, 4000, 5, 400, 100, 400, 10, 60, "flash");
        return new ExperienceInjectionHook(new ExperienceProvider(mapper, properties), "MAIN");
    }

    private static RunnableConfig config() {
        return RunnableConfig.builder().threadId("experience-test").build();
    }

    /** 通过反射读取命令中的消息，框架没有公开读取方法。 */
    @SuppressWarnings("unchecked")
    private static List<Message> messagesOf(AgentCommand command) throws ReflectiveOperationException {
        Field field = AgentCommand.class.getDeclaredField("messages");
        field.setAccessible(true);
        return (List<Message>) field.get(command);
    }
}