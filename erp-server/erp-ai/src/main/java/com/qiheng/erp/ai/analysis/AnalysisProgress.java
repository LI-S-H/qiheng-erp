package com.qiheng.erp.ai.analysis;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 一次分析任务的过程通道；把分析智能体的思路、工具调用和最终结论发回主智能体事件流。
 *
 * <p>由主智能体侧构造并放入工具上下文，分析智能体侧只读取使用。所有事件都带父级的工具调用编号，
 * 前端据此把过程归属到同一次分析之下。</p>
 *
 * @author Li
 * @since 2026-11-10
 */
public final class AnalysisProgress {

    /** 工具上下文与运行配置中共用的键名。 */
    public static final String CONTEXT_KEY = "analysisProgress";

    private final String parentToolCallId;
    private final BiConsumer<String, Map<String, Object>> emit;
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private String goal = "";

    /** 构造过程通道；父级工具调用编号用于把过程归属到对应的分析工具调用。 */
    public AnalysisProgress(String parentToolCallId, BiConsumer<String, Map<String, Object>> emit) {
        this.parentToolCallId = parentToolCallId;
        this.emit = emit;
    }

    /** 取得父级工具调用编号。 */
    public String parentToolCallId() {
        return parentToolCallId;
    }

    /**
     * 标记分析开始并记录主智能体交代的任务。
     *
     * @param goal 交办的分析目标
     */
    public synchronized void start(String goal) {
        this.goal = goal == null ? "" : goal;
        var data = new LinkedHashMap<String, Object>();
        data.put("toolCallId", parentToolCallId);
        data.put("goal", this.goal);
        emit.accept("analysis_started", data);
    }

    /**
     * 发布一轮模型输出：思路文字与本轮调用的工具。
     *
     * @param text 模型输出文字，可为空
     * @param tools 本轮调用的工具，每项含 name 与 label
     */
    public synchronized void step(String text, List<Map<String, Object>> tools) {
        var step = new LinkedHashMap<String, Object>();
        step.put("text", text == null ? "" : text);
        step.put("tools", tools == null ? List.of() : List.copyOf(tools));
        steps.add(step);
        var data = new LinkedHashMap<String, Object>();
        data.put("toolCallId", parentToolCallId);
        data.put("text", step.get("text"));
        data.put("tools", step.get("tools"));
        emit.accept("analysis_step", data);
    }

    /**
     * 发布分析结论与完整过程；结论由服务层解析交接结果后给出，与交还主智能体的内容一致。
     *
     * @param completion 完成情况
     * @param summary 结论文字
     */
    public synchronized void summary(String completion, String summary) {
        var data = new LinkedHashMap<String, Object>();
        data.put("toolCallId", parentToolCallId);
        data.put("goal", goal);
        data.put("steps", List.copyOf(steps));
        data.put("completion", completion == null ? "" : completion);
        data.put("summary", summary == null ? "" : summary);
        emit.accept("analysis_summary", data);
    }
}
