package com.qiheng.erp.ai.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.assertThat;

class RunEventChannelTest {
    @Test
    void pausedRunResubscribesAsWaitingInsteadOfFailure() {
        var channel = new RunEventChannel("r");
        channel.publish("sales_order_draft", Map.of("callId", "sales-1", "action", "CREATE"));
        channel.publish("run_completed", Map.of("runId", "r", "status", "WAITING_USER"));
        var events = channel.subscribe().collectList().block();
        assertThat(events).extracting(RunEventChannel.Event::event).containsExactly("run_snapshot", "run_completed");
        assertThat(events.getLast().data()).containsEntry("status", "WAITING_USER").doesNotContainKey("eventSeq");
    }

    @Test
    void failedRunResubscriptionPreservesFailureDetails() {
        var channel = new RunEventChannel("r");
        channel.publish("run_failed", Map.of("code", "MODEL_CALL_FAILED", "message", "模型调用失败"));
        var events = channel.subscribe().collectList().block();
        assertThat(events).extracting(RunEventChannel.Event::event).containsExactly("run_snapshot", "run_failed");
        assertThat(events.getLast().data()).containsEntry("status", "FAILED")
                .containsEntry("code", "MODEL_CALL_FAILED").containsEntry("message", "模型调用失败");
    }
    @Test
    void snapshotAndLaterEventsDoNotRestartOrLoseContent() {
        var channel = new RunEventChannel("r");
        channel.publish("text_delta", Map.of("messageId", "m", "delta", "第一段"));
        List<RunEventChannel.Event> events = new CopyOnWriteArrayList<>();
        var subscription = channel.subscribe().subscribe(events::add);
        channel.publish("text_delta", Map.of("messageId", "m", "delta", "第二段"));
        channel.publish("run_completed", Map.of("runId", "r", "status", "COMPLETED"));
        assertThat(events).extracting(RunEventChannel.Event::event).containsExactly("run_snapshot", "text_delta", "run_completed");
        assertThat(events.getFirst().data()).containsEntry("eventSeq", 1L);
        assertThat(events.get(1).data()).containsEntry("eventSeq", 2L);
        subscription.dispose();
        List<RunEventChannel.Event> restored = new CopyOnWriteArrayList<>();
        channel.subscribe().subscribe(restored::add);
        assertThat(restored).extracting(RunEventChannel.Event::event).containsExactly("run_snapshot", "run_completed");
        assertThat(restored.getFirst().data().toString()).contains("第一段第二段", "COMPLETED");
    }

    @Test
    void disconnectDoesNotStopChannelAndAnswerClearsQuestion() {
        var channel = new RunEventChannel("r");
        var subscription = channel.subscribe().subscribe();
        subscription.dispose();
        channel.publish("question", Map.of("questionRequestId", "q", "questions", List.of()));
        assertThat(channel.snapshot()).containsEntry("status", "WAITING_USER");
        channel.answered("old");
        assertThat(channel.snapshot()).containsEntry("status", "WAITING_USER");
        channel.answered("q");
        assertThat(channel.snapshot()).containsEntry("status", "RUNNING").containsEntry("question", null);
    }

    @Test
    void completedTextCannotBeCorruptedByLateDelta() {
        var channel = new RunEventChannel("r");
        channel.publish("text_delta", Map.of("messageId", "m", "delta", "前半"));
        channel.publish("text_completed", Map.of("messageId", "m", "text", "前半和后半"));
        channel.publish("text_delta", Map.of("messageId", "m", "delta", "重复"));
        assertThat(channel.snapshot().get("items").toString()).contains("前半和后半", "completed=true").doesNotContain("重复");
    }

    @Test
    void questionKeepsItsPositionAndUsesRequestIdWhenCallIdIsMissing() {
        var channel = new RunEventChannel("r");
        channel.publish("text_delta", Map.of("messageId", "before", "delta", "先询问"));
        channel.publish("question", Map.of("questionRequestId", "q", "toolCallId", "call", "questions", List.of()));
        channel.publish("question", Map.of("questionRequestId", "q", "toolCallId", "call", "questions", List.of()));
        channel.answered("q");
        channel.publish("text_delta", Map.of("messageId", "after", "delta", "继续"));
        channel.publish("question", Map.of("questionRequestId", "q2", "questions", List.of()));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) channel.snapshot().get("items");
        assertThat(items).extracting(item -> item.get("id")).containsExactly("before", "call", "after", "q2");
        assertThat(items.get(1)).containsEntry("type", "clarification").containsEntry("questionRequestId", "q");
        assertThat(channel.snapshot()).containsEntry("status", "WAITING_USER");
    }

    @Test
    void analysisSnapshotKeepsBlockPositionAndCompletedSteps() {
        var channel = new RunEventChannel("r");
        channel.publish("analysis_started", Map.of("toolCallId", "c", "goal", "分析库存"));
        channel.publish("analysis_step", Map.of("toolCallId", "c", "text", "先看数据",
                "tools", List.of(Map.of("name", "query_sql_data", "label", "查询业务数据"))));
        channel.publish("analysis_summary", Map.of("toolCallId", "c", "goal", "分析库存",
                "steps", List.of(Map.of("text", "先看数据", "tools", List.of())), "completion", "COMPLETED", "summary", "库存充足"));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) channel.snapshot().get("items");
        assertThat(items).hasSize(1);
        assertThat(items.getFirst()).containsEntry("type", "analysis").containsEntry("id", "c")
                .containsEntry("goal", "分析库存").containsEntry("summary", "库存充足");
        assertThat(items.getFirst().get("steps").toString()).contains("先看数据");
    }

    @Test
    void analysisStepWithoutStartedEventIsIgnored() {
        var channel = new RunEventChannel("r");
        channel.publish("analysis_step", Map.of("toolCallId", "c", "text", "孤立步骤", "tools", List.of()));
        assertThat(channel.snapshot()).containsEntry("items", List.of());
    }

    @Test
    void reportSnapshotPreservesProgressAndFinalMessageWithoutLeakingContent() {
        var channel = new RunEventChannel("r");
        channel.publish("report_progress", Map.of("toolCallId", "c", "taskId", "task-uuid", "phase", "WRITING",
                "text", "正在编写", "stepSeq", 1));
        channel.publish("report_progress", Map.of("toolCallId", "c", "phase", "REVIEWING",
                "text", "正在审阅", "stepSeq", 2));
        channel.publish("report_ready", Map.of("toolCallId", "c", "requestId", "r",
                "reportId", "rpt-1", "title", "库存简报", "placeholder", "[[REPORT:库存简报]]",
                "content", "报告正文"));
        channel.publish("run_completed", Map.of("runId", "r", "status", "COMPLETED", "finalMessageId", "final"));

        var events = channel.subscribe().collectList().block();
        assertThat(events).extracting(RunEventChannel.Event::event).containsExactly("run_snapshot", "run_completed");
        assertThat(events.getFirst().data()).containsEntry("finalMessageId", "final");
        assertThat(events.getFirst().data().get("items").toString())
                .contains("type=report", "id=c", "taskId=task-uuid", "正在编写", "正在审阅", "rpt-1")
                .doesNotContain("报告正文");
        assertThat(events.getLast().data()).containsEntry("finalMessageId", "final");
    }
}
