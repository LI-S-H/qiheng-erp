package com.qiheng.erp.ai.report.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import java.util.List;
import java.util.Map;
import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/** 组装报告节点及审核后的路由；每次运行使用独立状态。 */
public final class ReportGraph {
    static final String WORK = "reportWork";
    private final CompiledGraph graph;

    @FunctionalInterface
    public interface Writer {
        String write(Map<String, Object> state) throws Exception;
    }

    @FunctionalInterface
    public interface Reviewer {
        Review review(Map<String, Object> state) throws Exception;
    }

    @FunctionalInterface
    public interface Preparer {
        Map<String, Object> prepare(Map<String, Object> input) throws Exception;
    }

    /** 审核分数、优点和问题。 */
    public record Review(double score, List<String> strengths, List<Issue> issues) {}

    /** 一项审核问题及修改建议。 */
    public record Issue(String location, String problem, String reason, String suggestion) {}

    public ReportGraph(Writer writer, Reviewer reviewer) throws Exception {
        this(writer, reviewer, input -> Map.of());
    }

    /** 保留独立报告图入口，供现有图测试和内部调用使用。 */
    public ReportGraph(Writer writer, Reviewer reviewer, Preparer preparer) throws Exception {
        this(new ReportNodes.Prepare(preparer), new ReportNodes.Write(writer),
                new ReportNodes.ReviewNode(reviewer), new ReportNodes.Decide());
    }

    /** 节点负责具体工作，图只定义顺序与条件边。 */
    public ReportGraph(NodeAction prepare, NodeAction write, NodeAction review, NodeAction decide) throws Exception {
        StateGraph definition = new StateGraph("report", () -> Map.of(WORK, new ReplaceStrategy()))
                .addNode("prepare", node_async(prepare))
                .addNode("write", node_async(write))
                .addNode("review", node_async(review))
                .addNode("decide", node_async(decide))
                .addEdge(START, "prepare")
                .addEdge("prepare", "write")
                .addEdge("write", "review")
                .addEdge("review", "decide")
                .addConditionalEdges("decide", edge_async(state -> {
                    if ("REVISE".equals(ReportNodes.work(state).get("status"))) {
                        return "revise";
                    }
                    return "end";
                }), Map.of("revise", "write", "end", END));
        graph = definition.compile(CompileConfig.builder().build());
    }

    /** 主智能体报告流程：每个阶段的判断分别路由，避免混合不同错误。 */
    public ReportGraph(NodeAction prepare, NodeAction prepareDecide, NodeAction write, NodeAction writeDecide,
                       NodeAction review, NodeAction reviewDecide) throws Exception {
        StateGraph definition = new StateGraph("report", () -> Map.of(WORK, new ReplaceStrategy()))
                .addNode("prepare", node_async(prepare))
                .addNode("prepareDecide", node_async(prepareDecide))
                .addNode("write", node_async(write))
                .addNode("writeDecide", node_async(writeDecide))
                .addNode("review", node_async(review))
                .addNode("reviewDecide", node_async(reviewDecide))
                .addEdge(START, "prepare")
                .addEdge("prepare", "prepareDecide")
                .addConditionalEdges("prepareDecide", edge_async(state -> route(state)),
                        Map.of("WRITE", "write", "END", END))
                .addEdge("write", "writeDecide")
                .addConditionalEdges("writeDecide", edge_async(state -> route(state)),
                        Map.of("REVIEW", "review", "END", END))
                .addEdge("review", "reviewDecide")
                .addConditionalEdges("reviewDecide", edge_async(state -> route(state)),
                        Map.of("WRITE", "write", "REVIEW", "review", "END", END));
        graph = definition.compile(CompileConfig.builder().build());
    }

    /** 仅从上一阶段判断结果读取下一条边。 */
    private static String route(com.alibaba.cloud.ai.graph.OverAllState state) {
        return String.valueOf(ReportNodes.work(state).get("route"));
    }

    /** 执行报告图并返回最终状态。 */
    public Map<String, Object> run(Map<String, Object> input) {
        var outputs = graph.stream(Map.of(WORK, Map.of("input", input))).collectList().block();
        if (outputs == null || outputs.isEmpty()) {
            throw new IllegalStateException("报告图未返回结果");
        }
        return Map.copyOf(ReportNodes.work(outputs.getLast().state()));
    }
}
