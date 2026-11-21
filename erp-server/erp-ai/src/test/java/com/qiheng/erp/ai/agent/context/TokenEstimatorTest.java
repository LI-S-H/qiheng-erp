package com.qiheng.erp.ai.agent.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * token 估算契约：中文按字、其他字符按四字符折算，工具调用与响应必须计入。
 *
 * @author Li
 * @since 2026-11-20
 */
class TokenEstimatorTest {

    @Test
    void chineseCountsOneTokenPerCharacter() {
        assertThat(TokenEstimator.estimate("你好世界")).isEqualTo(4);
    }

    @Test
    void asciiCountsFourCharactersPerToken() {
        assertThat(TokenEstimator.estimate("abcd")).isEqualTo(1);
        assertThat(TokenEstimator.estimate("abcde")).isEqualTo(2);
    }

    @Test
    void mixedTextAddsBothParts() {
        assertThat(TokenEstimator.estimate("ab中文")).isEqualTo(3);
    }

    @Test
    void messageKeepsFixedOverhead() {
        assertThat(TokenEstimator.estimate(new UserMessage("中文"))).isEqualTo(6);
    }

    @Test
    void toolCallsAreCountedWithTheirArguments() {
        var call = new AssistantMessage.ToolCall("id", "function", "query", "{}");
        var message = AssistantMessage.builder().content("").toolCalls(List.of(call)).build();

        assertThat(TokenEstimator.estimate(message)).isEqualTo(15);
    }

    @Test
    void emptyMessagesCountZero() {
        assertThat(TokenEstimator.estimate(List.of())).isZero();
    }
}