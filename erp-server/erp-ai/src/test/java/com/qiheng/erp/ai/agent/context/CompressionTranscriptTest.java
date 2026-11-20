package com.qiheng.erp.ai.agent.context;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 摘要转录的降级契约：预算充足时完整保留，预算不足时按轮次逐级缩短，最新一轮不整轮省略。
 *
 * @author Li
 * @since 2026-11-20
 */
class CompressionTranscriptTest {

    private static final String TOOL_RESULT = "A".repeat(3000) + "END";

    @Test
    void fullBudgetKeepsEverything() {
        var result = CompressionTranscript.render(history(), 10_000, 1000, 2000);

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("[用户]: 分析库存");
        assertThat(result.text()).contains("END");
        assertThat(result.text()).doesNotContain("已省略");
        assertThat(result.omittedExchanges()).isZero();
    }

    @Test
    void smallBudgetShortensOldestToolResultFirst() {
        var result = CompressionTranscript.render(history(), 400, 1000, 2000);

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("[工具结果已省略]");
        assertThat(result.text()).contains("[用户]: 继续");
    }

    @Test
    void tightBudgetKeepsUserTextOfNewestExchange() {
        var result = CompressionTranscript.render(history(), 40, 1000, 2000);

        assertThat(result).isNotNull();
        assertThat(result.text()).contains("[用户]: 继续");
        assertThat(result.text()).doesNotContain("[工具结果");
    }

    @Test
    void returnsNullWhenEvenNewestExchangeDoesNotFit() {
        assertThat(CompressionTranscript.render(history(), 1, 1000, 2000)).isNull();
    }

    @Test
    void toolResultIsClippedBeforeBeingRemoved() {
        var result = CompressionTranscript.render(history(), 600, 1000, 2000);

        assertThat(result).isNotNull();
        // 截断层保留前后各一半，并注明省略的字符数。
        assertThat(result.text()).contains("个字符已省略");
        assertThat(result.text()).contains("END");
    }

    @Test
    void omittedCountIsReported() {
        var result = CompressionTranscript.render(history(), 10, 1000, 2000);

        assertThat(result).isNotNull();
        assertThat(result.omittedExchanges()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void previousSummaryStaysWholeAtTheFront() {
        var messages = new ArrayList<Message>();
        messages.add(new UserMessage("【前情摘要】\n## 目标\n分析库存\n## 下一步\n继续"));
        messages.addAll(history());

        var result = CompressionTranscript.render(messages, 70, 1000, 2000);

        assertThat(result).isNotNull();
        // 旧摘要单独放在最前，不参与轮次降级；其它轮次已经降到只保留用户文本。
        assertThat(result.text()).startsWith("[已有摘要]:");
        assertThat(result.text()).contains("## 下一步\n继续");
        assertThat(result.text()).doesNotContain("查到了");
    }

    @Test
    void summaryOnlyHistoryHasNothingToCompact() {
        var messages = List.<Message>of(new UserMessage("【前情摘要】\n## 目标\n分析库存"));

        assertThat(CompressionTranscript.render(messages, 400, 1000, 2000)).isNull();
    }

    private static List<Message> history() {
        var call = new AssistantMessage.ToolCall("call-1", "function", "query_sql_data", "{\"goal\":\"库存\"}");
        return List.of(
                new UserMessage("分析库存"),
                AssistantMessage.builder().content("先查数据").toolCalls(List.of(call)).build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("call-1", "query_sql_data", TOOL_RESULT)))
                        .build(),
                new AssistantMessage("查到了"),
                new UserMessage("继续"),
                new AssistantMessage("好的"));
    }
}