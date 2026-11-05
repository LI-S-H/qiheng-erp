package com.qiheng.erp.ai.agent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 单次运行的可重订阅通道；快照与订阅在同一把锁内完成。 */
public final class RunEventChannel {
    public record Event(String event, Map<String, Object> data) {}

    private String runId;
    private final List<FluxSink<Event>> subscribers = new ArrayList<>();
    private final Map<String, Map<String, Object>> items = new LinkedHashMap<>();
    private String status = "RUNNING";
    private Map<String, Object> question;
    private long eventSeq;
    private boolean closed;
    private long closedAt;
    private int textChars;
    private boolean snapshotTruncated;

    public RunEventChannel(String runId) { this.runId = Objects.requireNonNull(runId); }

    /** 发布事件并更新快照；调用者只允许为本轮发布已经排序的事件。 */
    public synchronized void publish(String name, Map<String, Object> data) {
        if (closed) {
            return;
        }
        var body = new LinkedHashMap<>(data);
        body.put("eventSeq", ++eventSeq);
        switch (name) {
            case "run_started" -> runId = String.valueOf(data.get("runId"));
            case "text_delta" -> {
                String id = (String) data.get("messageId");
                if (!items.containsKey("text:" + id) && items.size() >= 1000) {
                    snapshotTruncated = true;
                } else {
                    var item = items.computeIfAbsent("text:" + id, key -> text(id));
                    String delta = String.valueOf(data.get("delta"));
                    if (Boolean.TRUE.equals(item.get("completed"))) {
                        break;
                    }
                    if (textChars + delta.length() <= 1_000_000) { item.put("text", item.get("text") + delta); textChars += delta.length(); } else {
                        snapshotTruncated = true;
                    }
                }
            }
            case "text_completed" -> {
                String id = (String) data.get("messageId");
                if (!items.containsKey("text:" + id) && items.size() >= 1000) {
                    snapshotTruncated = true;
                } else {
                    var item = items.computeIfAbsent("text:" + id, key -> text(id));
                    String value = String.valueOf(data.get("text"));
                    int previous = String.valueOf(item.get("text")).length();
                    if (textChars - previous + value.length() <= 1_000_000) { item.put("text", value); item.put("completed", true); textChars += value.length() - previous; } else {
                        snapshotTruncated = true;
                    }
                }
            }
            case "tool_started" -> {
                String id = (String) data.get("toolCallId");
                var item = new LinkedHashMap<String, Object>();
                item.put("type", "tool"); item.put("id", id); item.put("label", data.get("label"));
                item.put("status", "running"); item.put("durationMs", null);
                if (items.size() < 1000 || items.containsKey("tool:" + id)) {
                    items.put("tool:" + id, item);
                } else {
                    snapshotTruncated = true;
                }
            }
            case "tool_finished" -> {
                var item = items.get("tool:" + data.get("toolCallId"));
                if (item != null) {
                    item.put("status", "failed".equals(data.get("status")) ? "failed" : "done");
                    item.put("durationMs", data.get("durationMs"));
                    if (data.get("label") != null) {
                        item.put("label", data.get("label"));
                    }
                }
            }
            case "question" -> {
                status = "WAITING_USER";
                question = Map.copyOf(data);
                Object callId = data.get("toolCallId");
                String id = callId instanceof String value && !value.isBlank()
                        ? value : String.valueOf(data.get("questionRequestId"));
                String key = "clarification:" + id;
                if (items.size() < 1000 || items.containsKey(key)) {
                    var item = new LinkedHashMap<String, Object>();
                    item.put("type", "clarification");
                    item.put("id", id);
                    item.put("questionRequestId", data.get("questionRequestId"));
                    item.put("questions", data.get("questions"));
                    items.put(key, item);
                } else {
                    snapshotTruncated = true;
                }
            }
            case "question_answered" -> { status = "RUNNING"; question = null; }
            case "run_completed" -> { status = "COMPLETED"; question = null; }
            case "run_failed" -> { status = "FAILED"; question = null; }
        }
        Event event = new Event(name, Map.copyOf(body));
        for (var subscriber : List.copyOf(subscribers)) {
            subscriber.next(event);
        }
        if (name.equals("run_completed") || name.equals("run_failed")) {
            closed = true;
            closedAt = System.currentTimeMillis();
            for (var subscriber : List.copyOf(subscribers)) {
                subscriber.complete();
            }
            subscribers.clear();
        }
    }

    /** 用户提交回答成功后撤销有效追问；不改变已展示的历史。 */
    public synchronized void answered(String questionRequestId) {
        // 答案提交期间可能已出现新的追问，不能清除新问题。
        if (!closed && question != null && questionRequestId.equals(question.get("questionRequestId"))) {
            publish("question_answered", Map.of("questionRequestId", questionRequestId));
        }
    }

    /** 已结束任务只短暂保留，供断线后读取结束状态。 */
    public synchronized boolean expired() { return closed && System.currentTimeMillis() - closedAt > 120_000; }

    public synchronized boolean isClosed() { return closed; }

    public synchronized Map<String, Object> snapshot() {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("runId", runId); snapshot.put("status", status);
        snapshot.put("items", items.values().stream().map(LinkedHashMap::new).toList());
        snapshot.put("question", question); snapshot.put("eventSeq", eventSeq);
        snapshot.put("snapshotTruncated", snapshotTruncated);
        return snapshot;
    }

    /** 原子发送快照并登记订阅，快照之后的事件不会遗漏。 */
    public Flux<Event> subscribe() {
        return Flux.create(sink -> {
            synchronized (this) {
                sink.next(new Event("run_snapshot", snapshot()));
                if (closed) {
                    sink.next(new Event("COMPLETED".equals(status) ? "run_completed" : "run_failed", Map.of("runId", runId, "status", status)));
                    sink.complete();
                } else {
                    subscribers.add(sink);
                    sink.onDispose(() -> remove(sink));
                }
            }
        });
    }

    private synchronized void remove(FluxSink<Event> sink) { subscribers.remove(sink); }

    private static Map<String, Object> text(String id) {
        var item = new LinkedHashMap<String, Object>();
        item.put("type", "text"); item.put("id", id); item.put("text", "");
        return item;
    }
}
