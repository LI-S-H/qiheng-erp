package com.qiheng.erp.ai.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 单据工具消息的解析契约：调用与结果都输出 ORDER_TOOL，普通工具仍输出 TOOL_CALL。
 *
 * @author Li
 * @since 2026-11-25
 */
class VisibleMessageCodecTest {

    private final VisibleMessageCodec codec = new VisibleMessageCodec(new ObjectMapper());

    @Test
    void confirmationTextKeepsCallIdAndLegacyTextStillDecodes() {
        var linked = codec.toView(message("ASSISTANT_STEP", "{\"text\":\"确认前说明\",\"callId\":\"c1\"}")).content();
        assertThat(linked.type()).isEqualTo("TEXT");
        assertThat(linked.data()).containsEntry("text", "确认前说明").containsEntry("callId", "c1");
        var legacy = codec.toView(message("ASSISTANT_STEP", "\"历史说明\"")).content();
        assertThat(legacy.data()).containsEntry("text", "历史说明").doesNotContainKey("callId");
    }

    @Test
    void decodesOrderToolCall() {
        var content = codec.toView(message("TOOL_CALL",
                "{\"callId\":\"c1\",\"name\":\"prepare_sales_order_draft\",\"label\":\"创建销售草稿单\","
                        + "\"action\":\"CREATE\",\"orderType\":\"SALES\",\"status\":\"waiting\",\"arguments\":\"{}\"}"))
                .content();

        assertThat(content.type()).isEqualTo("ORDER_TOOL");
        assertThat(content.data()).containsEntry("callId", "c1").containsEntry("status", "waiting")
                .containsEntry("action", "CREATE");
    }

    @Test
    void decodesOrderToolResult() {
        var content = codec.toView(message("TOOL_RESULT",
                "{\"callId\":\"c1\",\"name\":\"prepare_sales_order_draft\",\"action\":\"CREATE\","
                        + "\"orderType\":\"SALES\",\"result\":\"{\\\"status\\\":\\\"created\\\",\\\"orderId\\\":\\\"9001\\\"}\"}"))
                .content();

        assertThat(content.type()).isEqualTo("ORDER_TOOL");
        assertThat(content.data()).containsEntry("callId", "c1").containsKey("result");
    }

    @Test
    void keepsPlainToolRow() {
        var content = codec.toView(message("TOOL_CALL",
                "{\"callId\":\"c2\",\"name\":\"query_sql_data\",\"label\":\"查询业务数据\","
                        + "\"status\":\"done\",\"durationMs\":\"10\"}"))
                .content();

        assertThat(content.type()).isEqualTo("TOOL_CALL");
    }

    /** 构造一条最小消息。 */
    private AiMessage message(String kind, String content) {
        var message = new AiMessage();
        message.setKind(kind);
        message.setContent(content);
        message.setRole("assistant");
        return message;
    }
}
