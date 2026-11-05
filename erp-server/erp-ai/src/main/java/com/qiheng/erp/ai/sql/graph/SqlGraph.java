package com.qiheng.erp.ai.sql.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.alibaba.cloud.ai.graph.action.NodeAction;

import java.time.Duration;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.WORK;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.update;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.work;

/** 一次取数的四个节点；工作状态整体替换，避免同一会话复用时混入上一轮字段。 */
public final class SqlGraph {
    private final CompiledGraph graph;

    /** 使用框架检查点保存节点状态；数据行由节点实现保存到数据集。 */
    public SqlGraph(BaseCheckpointSaver saver, NodeAction prepare, NodeAction generate,
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
        graph = definition.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .releaseThread(false).build());
    }

    /** 新查询传入完整工作状态，替换会话上一次查询的值。 */
    public Map<String, Object> start(String threadId, Map<String, Object> input) {
        return run(threadId, null, Map.of(WORK, input));
    }

    /** 通过框架指定检查点恢复，不手工读取或重建节点状态。 */
    public Map<String, Object> resume(String threadId, String checkpointId) {
        return run(threadId, checkpointId, Map.of());
    }

    /** 上层可靠保存结果后，清空最新工作状态，历史检查点由框架保留。 */
    public void clear(String threadId) throws Exception {
        graph.updateState(RunnableConfig.builder().threadId(threadId).build(), Map.of(WORK, Map.of()));
    }

    /** 运行图，返回最新工作状态。 */
    private Map<String, Object> run(String threadId, String checkpointId, Map<String, Object> input) {
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        if (checkpointId != null) {
            config = config.withCheckPointId(checkpointId);
        }
        var outputs = graph.stream(input, config).collectList().block(Duration.ofMinutes(5));
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
