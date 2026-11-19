package com.qiheng.erp.ai.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class VisibleMessageCodecTest {
    private final VisibleMessageCodec codec = new VisibleMessageCodec(new ObjectMapper());

    @Test void readsReportProcessWithoutExposingOtherFields() {
        var message = new AiMessage().setKind("REPORT_STEP").setContent(
                "{\"toolCallId\":\"call1\",\"taskId\":\"task-uuid\",\"status\":\"RUNNING\",\"steps\":[],\"secret\":\"hidden\"}");
        var content = codec.toView(message).content();
        assertThat(content.type()).isEqualTo("REPORT");
        assertThat(content.data()).containsEntry("toolCallId", "call1").containsEntry("taskId", "task-uuid")
                .containsEntry("status", "RUNNING")
                .doesNotContainKey("secret");
        message.setContent("{\"toolCallId\":\"old-call\",\"status\":\"RUNNING\",\"steps\":[]}");
        assertThat(codec.toView(message).content().data()).containsEntry("toolCallId", "old-call")
                .doesNotContainKey("taskId");
    }

    @Test void readsTextAndUserQuestionTools() throws Exception {
        var message = new AiMessage().setKind("USER_INPUT")
                .setContent(new ObjectMapper().writeValueAsString("{这是一段原文}"));
        assertThat(codec.toView(message).content().data()).containsEntry("text", "{这是一段原文}");
        message.setKind("TOOL_CALL").setContent("{\"name\":\"ask_user\",\"callId\":\"c1\",\"arguments\":\"{}\"}");
        assertThat(codec.toView(message).content().type()).isEqualTo("TOOL_CALL");
        message.setKind("TOOL_RESULT").setContent("{\"name\":\"ask_user\",\"callId\":\"c1\",\"result\":\"null\"}");
        assertThat(codec.toView(message).content().data()).containsEntry("result", "null");
    }

    @Test void readsStoredTextAndWrappedUserQuestionTools() {
        var message = new AiMessage().setKind("ASSISTANT_FINAL")
                .setContent("{\"type\":\"TEXT\",\"data\":{\"text\":\"历史文本\"}}");
        assertThat(codec.toView(message).content().data()).containsEntry("text", "历史文本");
        message.setContent("{\"text\":\"旧文本\"}");
        assertThat(codec.toView(message).content().data()).containsEntry("text", "旧文本");
        message.setKind("TOOL_CALL").setContent("{\"type\":\"TOOL_CALL\",\"data\":{\"name\":\"ask_user\",\"callId\":\"c1\",\"arguments\":\"{}\"}}");
        assertThat(codec.toView(message).content().type()).isEqualTo("TOOL_CALL");
        message.setKind("TOOL_RESULT").setContent("{\"type\":\"TOOL_RESULT\",\"data\":{\"name\":\"ask_user\",\"callId\":\"c1\",\"result\":\"yes\"}}");
        assertThat(codec.toView(message).content().data()).containsEntry("result", "yes");
    }

    @Test void readsAnalysisProcessWithGoalStepsAndSummary() {
        var message = new AiMessage().setKind("ANALYSIS_STEP").setContent(
                "{\"toolCallId\":\"c1\",\"goal\":\"分析库存\",\"steps\":[{\"text\":\"先看数据\","
                        + "\"tools\":[{\"name\":\"query_sql_data\",\"label\":\"查询业务数据\"}]}],"
                        + "\"completion\":\"COMPLETED\",\"summary\":\"库存充足\"}");
        var content = codec.toView(message).content();
        assertThat(content.type()).isEqualTo("ANALYSIS");
        assertThat(content.data()).containsEntry("toolCallId", "c1").containsEntry("goal", "分析库存")
                .containsEntry("completion", "COMPLETED").containsEntry("summary", "库存充足");
        assertThat(content.data().get("steps").toString()).contains("查询业务数据");
    }

    /** 普通工具的结构化展示行要原样交给前端，且不暴露参数与结果。 */
    @Test void exposesStructuredToolRowWithoutInternalFields() {
        var message = new AiMessage().setKind("TOOL_CALL").setContent(
                "{\"callId\":\"c2\",\"name\":\"query_sql_data\",\"label\":\"查询业务数据\","
                        + "\"status\":\"done\",\"durationMs\":\"15036\"}");
        var view = codec.toView(message);
        assertThat(view.content().type()).isEqualTo("TOOL_CALL");
        assertThat(view.content().data())
                .containsEntry("name", "query_sql_data")
                .containsEntry("label", "查询业务数据")
                .containsEntry("status", "done")
                .containsEntry("durationMs", "15036")
                .doesNotContainKeys("arguments", "result");
    }

    @Test void legacyObjectsAndOtherToolsDoNotExposeInternalContent() {
        var message = new AiMessage().setKind("ASSISTANT_FINAL").setContent("{\"type\":\"PLAN\",\"data\":{\"plan\":{}}}");
        assertThat(codec.toView(message).content().data()).containsEntry("text", "该历史消息暂时无法展示。");
        message.setKind("TOOL_RESULT").setContent("{\"name\":\"query_sql_data\",\"result\":\"secret\"}");
        assertThat(codec.toView(message).content().data()).containsEntry("text", "");
    }
}
