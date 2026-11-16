package com.qiheng.erp.ai.sql.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.alibaba.cloud.ai.graph.action.NodeAction;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.WORK;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.work;

/** 一次取数的四个节点；工作状态整体替换，避免同一会话复用时混入上一轮字段。 */
public final class SqlGraph {
    private final CompiledGraph graph;

    /** 构建 SQL 图，数据行由节点保存到数据集。 */
    public SqlGraph(NodeAction prepare, NodeAction generate,
                    NodeAction execute, NodeAction inspect) throws Exception {
        StateGraph definition = new StateGraph("sql-research", () -> Map.of(WORK, new ReplaceStrategy()))
                .addNode("prepare", node_async(prepare))
                .addNode("generate", node_async(generate))
                .addNode("execute", node_async(execute))
                .addNode("inspect", node_async(inspect))
                .addEdge(START, "prepare")
                .addConditionalEdges("prepare", edge_async(state -> route(work(state), "generate")),
                        Map.of("generate", "generate", "end", END))
                .addConditionalEdges("generate", edge_async(state -> route(work(state), "execute")),
                        Map.of("execute", "execute", "retry", "generate", "end", END))
                .addConditionalEdges("execute", edge_async(state -> route(work(state), "inspect")),
                        Map.of("inspect", "inspect", "retry", "generate", "end", END))
                .addConditionalEdges("inspect", edge_async(state -> route(work(state), "end")),
                        Map.of("retry", "generate", "end", END));
        graph = definition.compile(CompileConfig.builder().build());
    }

    /** 每次查询使用独立的运行状态，不保存恢复检查点。 */
    public Map<String, Object> start(Map<String, Object> input) {
        var outputs = graph.stream(Map.of(WORK, input)).collectList().block();
        if (outputs == null || outputs.isEmpty()) {
            throw new IllegalStateException("SQL 图未返回结果");
        }
        return work(outputs.getLast().state());
    }

    private static String route(Map<String, Object> work, String next) {
        return switch (String.valueOf(work.getOrDefault("route", "next"))) {
            case "retry" -> "retry";
            case "end" -> "end";
            default -> next;
        };
    }

}
