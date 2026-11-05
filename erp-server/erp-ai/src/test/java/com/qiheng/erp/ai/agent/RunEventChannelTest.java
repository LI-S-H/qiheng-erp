package com.qiheng.erp.ai.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.assertj.core.api.Assertions.assertThat;

class RunEventChannelTest {
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
}
