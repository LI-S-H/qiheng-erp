package com.qiheng.erp.ai.agent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单次运行的可重订阅通道；快照与订阅在同一把锁内完成。
 *
 * <p>通道同时承担两件事：把事件实时发给订阅者，并保留一份进程内快照，
 * 供页面断线后重连时恢复本轮的运行过程。</p>
 *
 * @author Li
 * @since 2026-10-27
 */
public final class RunEventChannel {

    /** 快照中最多保留的条目数，超出后只保留提示而不继续堆积。 */
    private static final int MAX_ITEMS = 1000;

    /** 快照中最多保留的文本字符数，超出后标记为截断。 */
    private static final int MAX_TEXT_CHARS = 1_000_000;

    /** 通道向订阅者推送的一次事件。 */
    public record Event(String event, Map<String, Object> data) {
    }

    private String runId;
    private final List<FluxSink<Event>> subscribers = new ArrayList<>();
    private final Map<String, Map<String, Object>> items = new LinkedHashMap<>();
    private String status = "RUNNING";
    private Map<String, Object> question;
    private Map<String, Object> draft;
    private Map<String, Object> action;
    private Map<String, Object> salesDraft;
    private Map<String, Object> salesAction;
    private Map<String, Object> returnDraft;
    private Map<String, Object> returnAction;
    private long eventSeq;
    private boolean closed;
    private Event terminalEvent;
    private long closedAt;
    private int textChars;
    private boolean snapshotTruncated;
    private String finalMessageId;

    /**
     * 建立本轮运行通道。
     *
     * @param runId 本轮运行标识
     */
    public RunEventChannel(String runId) {
        this.runId = Objects.requireNonNull(runId);
    }

    /**
     * 发布事件并更新快照；调用者只允许为本轮发布已经排序的事件。
     *
     * @param name 事件名
     * @param data 事件数据
     */
    public synchronized void publish(String name, Map<String, Object> data) {
        if (closed) {
            return;
        }
        var body = new LinkedHashMap<>(data);
        body.put("eventSeq", ++eventSeq);
        applyToSnapshot(name, data);
        Event event = new Event(name, Map.copyOf(body));
        for (var subscriber : List.copyOf(subscribers)) {
            subscriber.next(event);
        }
        if (name.equals("run_completed") || name.equals("run_failed")) {
            terminalEvent = event;
            closed = true;
            closedAt = System.currentTimeMillis();
            for (var subscriber : List.copyOf(subscribers)) {
                subscriber.complete();
            }
            subscribers.clear();
        }
    }

    /** 按事件类型更新进程内快照；每个分支只维护自己关心的条目。 */
    private void applyToSnapshot(String name, Map<String, Object> data) {
        switch (name) {
            case "run_started" -> runId = String.valueOf(data.get("runId"));
            case "text_delta" -> applyTextDelta(data);
            case "text_completed" -> applyTextCompleted(data);
            case "tool_started" -> applyToolStarted(data);
            case "tool_finished" -> applyToolFinished(data);
            case "analysis_started" -> applyAnalysisStarted(data);
            case "analysis_step" -> analysisStep(data);
            case "analysis_summary" -> analysisSummary(data);
            case "report_progress", "report_ready" -> applyReport(name, data);
            case "question" -> applyQuestion(data);
            case "question_answered" -> applyTerminal("RUNNING");
            case "purchase_draft" -> applyDraft(data);
            case "purchase_action" -> applyAction(data);
            case "sales_order_draft" -> applySalesDraft(data);
            case "sales_order_action" -> applySalesAction(data);
            case "return_order_draft" -> applyReturnDraft(data);
            case "return_order_action" -> applyReturnAction(data);
            case "run_completed" -> {
                // 人工确认暂停不是最终完成，待确认内容必须留在快照里供刷新恢复。
                if ("WAITING_USER".equals(data.get("status"))) {
                    status = "WAITING_USER";
                } else {
                    if (data.get("finalMessageId") instanceof String id) {
                        finalMessageId = id;
                    }
                    applyTerminal("COMPLETED");
                }
            }
            case "run_failed" -> applyTerminal("FAILED");
            default -> {
                // 未纳入快照的事件只做实时推送。
            }
        }
    }

    /** 追加一段流式文字；超过容量上限时只标记截断。 */
    private void applyTextDelta(Map<String, Object> data) {
        String id = (String) data.get("messageId");
        String key = "text:" + id;
        if (!items.containsKey(key) && items.size() >= MAX_ITEMS) {
            snapshotTruncated = true;
            return;
        }
        var item = items.computeIfAbsent(key, ignored -> text(id));
        if (Boolean.TRUE.equals(item.get("completed"))) {
            return;
        }
        String delta = String.valueOf(data.get("delta"));
        if (textChars + delta.length() > MAX_TEXT_CHARS) {
            snapshotTruncated = true;
            return;
        }
        item.put("text", item.get("text") + delta);
        textChars += delta.length();
    }

    /** 用完整文字覆盖同一段流式内容，并标记该段已经结束。 */
    private void applyTextCompleted(Map<String, Object> data) {
        String id = (String) data.get("messageId");
        String key = "text:" + id;
        if (!items.containsKey(key) && items.size() >= MAX_ITEMS) {
            snapshotTruncated = true;
            return;
        }
        var item = items.computeIfAbsent(key, ignored -> text(id));
        String value = String.valueOf(data.get("text"));
        int previous = String.valueOf(item.get("text")).length();
        if (textChars - previous + value.length() > MAX_TEXT_CHARS) {
            snapshotTruncated = true;
            return;
        }
        item.put("text", value);
        item.put("completed", true);
        textChars += value.length() - previous;
    }

    /** 登记一条工具状态，供工作过程展示图标与耗时。 */
    private void applyToolStarted(Map<String, Object> data) {
        String id = (String) data.get("toolCallId");
        String key = "tool:" + id;
        var item = new LinkedHashMap<String, Object>();
        item.put("type", "tool");
        item.put("id", id);
        item.put("label", data.get("label"));
        // 工具名用于页面区分不同步骤的图标与分析入口。
        item.put("tool", data.get("tool"));
        item.put("status", "running");
        item.put("durationMs", null);
        if (items.size() < MAX_ITEMS || items.containsKey(key)) {
            items.put(key, item);
        } else {
            snapshotTruncated = true;
        }
    }

    /** 更新工具的最终状态与耗时。 */
    private void applyToolFinished(Map<String, Object> data) {
        var item = items.get("tool:" + data.get("toolCallId"));
        if (item == null) {
            return;
        }
        item.put("status", "failed".equals(data.get("status")) ? "failed" : "done");
        item.put("durationMs", data.get("durationMs"));
        if (data.get("label") != null) {
            item.put("label", data.get("label"));
        }
    }

    /** 建立分析过程条目；步骤与结论随后由对应事件补齐。 */
    private void applyAnalysisStarted(Map<String, Object> data) {
        String id = (String) data.get("toolCallId");
        String key = "analysis:" + id;
        if (items.size() >= MAX_ITEMS && !items.containsKey(key)) {
            snapshotTruncated = true;
            return;
        }
        var item = new LinkedHashMap<String, Object>();
        item.put("type", "analysis");
        item.put("id", id);
        item.put("goal", data.get("goal"));
        item.put("steps", new ArrayList<Map<String, Object>>());
        item.put("summary", null);
        item.put("completion", null);
        items.put(key, item);
    }

    /** 登记一次追问，并把本轮状态切换为等待用户。 */
    private void applyQuestion(Map<String, Object> data) {
        status = "WAITING_USER";
        clearPendingBusiness();
        question = Map.copyOf(data);
        Object callId = data.get("toolCallId");
        String id = callId instanceof String value && !value.isBlank()
                ? value : String.valueOf(data.get("questionRequestId"));
        String key = "clarification:" + id;
        if (items.size() >= MAX_ITEMS && !items.containsKey(key)) {
            snapshotTruncated = true;
            return;
        }
        var item = new LinkedHashMap<String, Object>();
        item.put("type", "clarification");
        item.put("id", id);
        item.put("questionRequestId", data.get("questionRequestId"));
        item.put("questions", data.get("questions"));
        items.put(key, item);
    }

    /** 登记一份待用户处置的采购草稿，并把本轮状态切换为等待用户。 */
    private void applyDraft(Map<String, Object> data) {
        status = "WAITING_USER";
        clearPendingBusiness();
        draft = Map.copyOf(data);
        String id = firstText(data.get("callId"), data.get("draftRequestId"));
        String key = "draft:" + id;
        if (items.size() >= MAX_ITEMS && !items.containsKey(key)) {
            snapshotTruncated = true;
            return;
        }
        var item = new LinkedHashMap<String, Object>();
        item.put("type", "purchase_draft");
        item.put("id", id);
        item.put("callId", data.get("callId"));
        item.put("action", data.get("action"));
        item.put("orderType", data.get("orderType"));
        item.put("arguments", data.get("arguments"));
        items.put(key, item);
    }

    /** 登记一次等待用户确认的采购单动作。 */
    private void applyAction(Map<String, Object> data) {
        status = "WAITING_USER";
        clearPendingBusiness();
        action = Map.copyOf(data);
        String id = firstText(data.get("callId"), data.get("actionRequestId"));
        String key = "action:" + id;
        if (items.size() >= MAX_ITEMS && !items.containsKey(key)) {
            snapshotTruncated = true;
            return;
        }
        var item = new LinkedHashMap<String, Object>();
        item.put("type", "purchase_action");
        item.put("id", id);
        item.put("callId", data.get("callId"));
        item.put("action", data.get("action"));
        item.put("purchaseOrderId", data.get("purchaseOrderId"));
        item.put("orderNo", data.get("orderNo"));
        item.put("reason", data.get("reason"));
        items.put(key, item);
    }

    /** 登记一份待确认的销售草稿；新事件以 callId 作为标识。 */
    private void applySalesDraft(Map<String, Object> data) {
        status = "WAITING_USER";
        clearPendingBusiness();
        salesDraft = Map.copyOf(data);
        String id = firstText(data.get("callId"), data.get("draftRequestId"));
        String key = "salesDraft:" + id;
        if (items.size() >= MAX_ITEMS && !items.containsKey(key)) {
            snapshotTruncated = true;
            return;
        }
        var item = new LinkedHashMap<String, Object>();
        item.put("type", "sales_order_draft");
        item.put("id", id);
        item.put("callId", data.get("callId"));
        item.put("action", data.get("action"));
        item.put("orderType", data.get("orderType"));
        item.put("arguments", data.get("arguments"));
        items.put(key, item);
    }

    /** 登记一次等待用户确认的销售单动作；新事件以 callId 作为标识。 */
    private void applySalesAction(Map<String, Object> data) {
        status = "WAITING_USER";
        clearPendingBusiness();
        salesAction = Map.copyOf(data);
        String id = firstText(data.get("callId"), data.get("actionRequestId"));
        String key = "salesAction:" + id;
        if (items.size() >= MAX_ITEMS && !items.containsKey(key)) {
            snapshotTruncated = true;
            return;
        }
        var item = new LinkedHashMap<String, Object>();
        item.put("type", "sales_order_action");
        item.put("id", id);
        item.put("callId", data.get("callId"));
        item.put("action", data.get("action"));
        item.put("salesOrderId", data.get("salesOrderId"));
        item.put("orderNo", data.get("orderNo"));
        item.put("reason", data.get("reason"));
        items.put(key, item);
    }

    /** 登记一份待确认的退货草稿。 */
    private void applyReturnDraft(Map<String, Object> data) {
        status = "WAITING_USER";
        clearPendingBusiness();
        returnDraft = Map.copyOf(data);
        String id = firstText(data.get("callId"), data.get("draftRequestId"));
        String key = "returnDraft:" + id;
        if (items.size() >= MAX_ITEMS && !items.containsKey(key)) {
            snapshotTruncated = true;
            return;
        }
        var item = new LinkedHashMap<String, Object>();
        item.put("type", "return_order_draft");
        item.put("id", id);
        item.put("callId", data.get("callId"));
        item.put("action", data.get("action"));
        item.put("orderType", data.get("orderType"));
        item.put("returnType", data.get("returnType"));
        item.put("returnOrderId", data.get("returnOrderId"));
        item.put("arguments", data.get("arguments"));
        items.put(key, item);
    }

    /** 登记一次等待用户确认的退货单动作。 */
    private void applyReturnAction(Map<String, Object> data) {
        status = "WAITING_USER";
        clearPendingBusiness();
        returnAction = Map.copyOf(data);
        String id = firstText(data.get("callId"), data.get("actionRequestId"));
        String key = "returnAction:" + id;
        if (items.size() >= MAX_ITEMS && !items.containsKey(key)) {
            snapshotTruncated = true;
            return;
        }
        var item = new LinkedHashMap<String, Object>();
        item.put("type", "return_order_action");
        item.put("id", id);
        item.put("callId", data.get("callId"));
        item.put("action", data.get("action"));
        item.put("returnOrderId", data.get("returnOrderId"));
        item.put("orderNo", data.get("orderNo"));
        item.put("reason", data.get("reason"));
        items.put(key, item);
    }

    /** 进入新的待确认状态前清理其他业务卡片，避免刷新后同时恢复多张过期卡片。 */
    private void clearPendingBusiness() {
        question = null;
        draft = null;
        action = null;
        salesDraft = null;
        salesAction = null;
        returnDraft = null;
        returnAction = null;
    }

    /** 取第一个非空文本标识；新旧事件结构都能定位同一条记录。 */
    private static String firstText(Object primary, Object fallback) {
        if (primary instanceof String value && !value.isBlank()) {
            return value;
        }
        if (fallback instanceof String value && !value.isBlank()) {
            return value;
        }
        return "";
    }

    /** 结束本轮状态并清除待回答的追问。 */
    private void applyTerminal(String terminalStatus) {
        status = terminalStatus;
        question = null;
        draft = null;
        action = null;
        salesDraft = null;
        salesAction = null;
        returnDraft = null;
        returnAction = null;
    }

    /**
     * 用户提交回答成功后撤销有效追问；不改变已展示的历史。
     *
     * @param questionRequestId 本次回答对应的问题编号
     */
    public synchronized void answered(String questionRequestId) {
        // 答案提交期间可能已出现新的追问，不能清除新问题。
        if (!closed && question != null && questionRequestId.equals(question.get("questionRequestId"))) {
            publish("question_answered", Map.of("questionRequestId", questionRequestId));
        }
    }

    /**
     * 判断已结束任务是否超过保留时间。
     *
     * @return 超过保留时间返回 true
     */
    public synchronized boolean expired() {
        return closed && System.currentTimeMillis() - closedAt > 120_000;
    }

    /**
     * 判断本轮是否已经结束。
     *
     * @return 已结束返回 true
     */
    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * 取得当前快照；断线重连时按此恢复运行过程。
     *
     * @return 快照内容
     */
    public synchronized Map<String, Object> snapshot() {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("runId", runId);
        snapshot.put("status", status);
        snapshot.put("items", items.values().stream().map(LinkedHashMap::new).toList());
        snapshot.put("question", question);
        snapshot.put("draft", draft);
        snapshot.put("action", action);
        snapshot.put("salesDraft", salesDraft);
        snapshot.put("salesAction", salesAction);
        snapshot.put("returnDraft", returnDraft);
        snapshot.put("returnAction", returnAction);
        snapshot.put("eventSeq", eventSeq);
        snapshot.put("snapshotTruncated", snapshotTruncated);
        if (finalMessageId != null) {
            snapshot.put("finalMessageId", finalMessageId);
        }
        return snapshot;
    }

    /**
     * 原子发送快照并登记订阅，快照之后的事件不会遗漏。
     *
     * @return 事件流
     */
    public Flux<Event> subscribe() {
        return Flux.create(sink -> {
            synchronized (this) {
                sink.next(new Event("run_snapshot", snapshot()));
                if (closed) {
                    var finished = new LinkedHashMap<String, Object>(terminalEvent.data());
                    // 快照已携带终态序号；重复发送相同序号会被客户端判为旧事件。
                    finished.remove("eventSeq");
                    finished.put("runId", runId);
                    finished.put("status", status);
                    if (finalMessageId != null) {
                        finished.put("finalMessageId", finalMessageId);
                    }
                    sink.next(new Event(terminalEvent.event(), Map.copyOf(finished)));
                    sink.complete();
                } else {
                    subscribers.add(sink);
                    sink.onDispose(() -> remove(sink));
                }
            }
        });
    }

    /** 移除已取消的订阅者。 */
    private synchronized void remove(FluxSink<Event> sink) {
        subscribers.remove(sink);
    }

    /** 追加一步分析过程；没有起始事件时不补建，避免快照里出现孤立片段。 */
    @SuppressWarnings("unchecked")
    private void analysisStep(Map<String, Object> data) {
        var item = items.get("analysis:" + data.get("toolCallId"));
        if (item == null || !(item.get("steps") instanceof List<?> steps)) {
            return;
        }
        var step = new LinkedHashMap<String, Object>();
        step.put("text", data.get("text"));
        step.put("tools", data.get("tools"));
        ((List<Map<String, Object>>) steps).add(step);
    }

    /** 用总结事件里的完整过程覆盖快照，重连后与实时面板内容一致。 */
    private void analysisSummary(Map<String, Object> data) {
        var item = items.get("analysis:" + data.get("toolCallId"));
        if (item == null) {
            return;
        }
        item.put("goal", data.get("goal"));
        item.put("steps", data.get("steps"));
        item.put("summary", data.get("summary"));
        item.put("completion", data.get("completion"));
    }

    /** 报告进度只保留展示数据，正文由独立详情接口提供。 */
    @SuppressWarnings("unchecked")
    private void applyReport(String name, Map<String, Object> data) {
        String id = (String) data.get("toolCallId");
        String key = "report:" + id;
        if (items.size() >= MAX_ITEMS && !items.containsKey(key)) {
            snapshotTruncated = true;
            return;
        }
        var item = items.computeIfAbsent(key, ignored -> new LinkedHashMap<>(
                Map.of("type", "report", "id", id, "steps", new ArrayList<Map<String, Object>>())));
        if (data.get("taskId") instanceof String taskId && !taskId.isBlank()) {
            item.put("taskId", taskId);
        }
        if ("report_ready".equals(name)) {
            item.put("report", Map.of("reportId", data.get("reportId"),
                    "requestId", data.get("requestId"), "toolCallId", id,
                    "title", data.get("title"), "placeholder", data.get("placeholder")));
            return;
        }
        var steps = new ArrayList<>((List<Map<String, Object>>) item.get("steps"));
        steps.add(Map.of("phase", data.get("phase"), "text", data.get("text"), "stepSeq", data.get("stepSeq")));
        // 已发出的快照不能因后续步骤写入而发生变化。
        item.put("steps", List.copyOf(steps));
    }

    /** 建立一段空文字条目。 */
    private static Map<String, Object> text(String id) {
        var item = new LinkedHashMap<String, Object>();
        item.put("type", "text");
        item.put("id", id);
        item.put("text", "");
        return item;
    }
}
