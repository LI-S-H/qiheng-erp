package com.qiheng.erp.examples;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.alibaba.cloud.ai.graph.state.strategy.AppendStrategy;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/**
 * 隔离的框架学习示例：无模型、数据库和真实业务工具，仅验证字段更新与检查点恢复。
 * MemorySaver 只在本进程存活；重建图对象不是进程重启后的持久恢复。
 */
public final class StateManagementDemo {
    private static final String TASK_ID = "taskId";
    private static final String REQUEST = "request";
    private static final String PARAMETERS = "parameters";
    private static final String PLAN = "plan";
    private static final String RESULT = "result";
    private static final String STATUS = "status";
    private static final String QUESTION = "question";
    private static final String TRACE = "trace";

    public static void main(String[] args) throws Exception {
        verifyUpdateStrategies();
        verifyHumanPauseAndResume();
        verifyResumeAfterExecutedNode();
        verifyThreadIsolation();
        System.out.println("ALL CHECKS PASSED: update strategies, Human resume, checkpoint continuation, thread isolation.");
    }

    /** 每个字段声明合并策略；首版业务状态使用替换，轨迹才使用追加。 */
    private static Map<String, KeyStrategy> strategies() {
        Map<String, KeyStrategy> keys = new HashMap<>();
        for (String key : List.of(TASK_ID, REQUEST, PARAMETERS, PLAN, RESULT, STATUS, QUESTION)) {
            keys.put(key, new ReplaceStrategy());
        }
        keys.put(TRACE, new AppendStrategy());
        return keys;
    }

    private static CompiledGraph graph(MemorySaver saver, AtomicInteger executions,
                                       boolean pauseAfterExecution) throws Exception {
        StateGraph graph = new StateGraph("erp-state-learning", StateManagementDemo::strategies)
                .addNode("planner", node_async(state -> Map.of(
                        PLAN, Map.of("goal", required(state, REQUEST), "warehouseId", warehouse(state)),
                        STATUS, "PLANNED", TRACE, List.of("planner"))))
                .addNode("check", node_async(state -> warehouse(state).isBlank()
                        ? Map.of(STATUS, "WAITING_HUMAN", QUESTION, "请补充仓库编号", TRACE, List.of("check"))
                        : Map.of(STATUS, "RUNNING", QUESTION, "", TRACE, List.of("check"))))
                // 实际暂停发生在进入该节点之前；人工补充由应用入口更新检查点。
                .addNode("human", node_async(state -> Map.of(TRACE, List.of("human"))))
                .addNode("execute", node_async(state -> {
                    executions.incrementAndGet();
                    return Map.of(RESULT, Map.of("warehouseId", warehouse(state), "datasetRef", "demo_dataset"),
                            TRACE, List.of("execute"));
                }))
                .addNode("reporter", node_async(state -> Map.of(STATUS, "COMPLETED", TRACE, List.of("reporter"))))
                .addEdge(START, "planner")
                .addEdge("planner", "check")
                .addConditionalEdges("check", edge_async(state -> warehouse(state).isBlank() ? "human" : "execute"),
                        Map.of("human", "human", "execute", "execute"))
                .addEdge("human", "planner")
                .addEdge("execute", "reporter")
                .addEdge("reporter", END);

        CompileConfig.Builder compile = CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .interruptBefore("human")
                .releaseThread(false);
        if (pauseAfterExecution) {
            compile.interruptAfter("execute");
        }
        return graph.compile(compile.build());
    }

    private static void verifyUpdateStrategies() {
        ReplaceStrategy replace = new ReplaceStrategy();
        Object value = replace.apply(Map.of("T1", "COMPLETED"), Map.of("T2", "COMPLETED"));
        check(value.equals(Map.of("T2", "COMPLETED")), "ReplaceStrategy 不深合并 Map");
        check(replace.apply(List.of("old"), List.of("new")).equals(List.of("new")), "ReplaceStrategy 替换整个 List");
        check(new AppendStrategy().apply(List.of("A"), List.of("B")).equals(List.of("A", "B")), "AppendStrategy 追加");
        System.out.println("PASS: Replace replaces an entire value; Append appends list items.");
    }

    private static void verifyHumanPauseAndResume() throws Exception {
        MemorySaver saver = new MemorySaver();
        AtomicInteger executions = new AtomicInteger();
        RunnableConfig config = config("task-human");
        CompiledGraph first = graph(saver, executions, false);
        run(first, input("task-human", Map.of("metric", "sales")), config);

        var paused = saver.get(config).orElseThrow();
        check("human".equals(paused.getNextNodeId()), "检查点必须停在 human，不能先结束图");
        check("WAITING_HUMAN".equals(paused.getState().get(STATUS)), "保留等待状态");
        check(executions.get() == 0, "补充前不能执行查询");
        System.out.println("PAUSED: status=WAITING_HUMAN, next=human, executions=0");

        // parameters 使用替换策略，因此显式合并旧值，避免仓库补充丢掉原 metric。
        Map<String, Object> parameters = new HashMap<>(parameters(paused.getState()));
        parameters.put("warehouseId", "wh-east");
        CompiledGraph restored = graph(saver, executions, false);
        // 只更新字段，不人为重算路由；恢复后执行 human，再沿已定义边回 planner。
        RunnableConfig resume = restored.updateState(config.withCheckPointId(paused.getId()),
                Map.of(PARAMETERS, parameters));
        run(restored, Map.of(), resume);

        var completed = saver.get(config).orElseThrow();
        check(END.equals(completed.getNextNodeId()), "恢复后正常结束");
        check("COMPLETED".equals(completed.getState().get(STATUS)), "恢复结果完成");
        check("sales".equals(parameters(completed.getState()).get("metric")), "人工更新保留原字段");
        check(executions.get() == 1, "补充后只执行一次");
        check(List.of("planner", "check", "human", "planner", "check", "execute", "reporter")
                .equals(completed.getState().get(TRACE)), "恢复沿用之前状态且重新规划");
        System.out.println("RESUMED: status=COMPLETED, executions=1, trace=" + completed.getState().get(TRACE));
    }

    private static void verifyResumeAfterExecutedNode() throws Exception {
        MemorySaver saver = new MemorySaver();
        AtomicInteger executions = new AtomicInteger();
        RunnableConfig config = config("task-after-execute");
        run(graph(saver, executions, true), input("task-after-execute", Map.of("warehouseId", "wh-west")), config);
        var paused = saver.get(config).orElseThrow();
        check("reporter".equals(paused.getNextNodeId()), "执行后的中断位置应是 reporter");
        check(paused.getState().containsKey(RESULT), "检查点保存执行结果");
        check(executions.get() == 1, "首次执行计数为一");

        run(graph(saver, executions, false), Map.of(), config.withCheckPointId(paused.getId()));
        check(executions.get() == 1, "从执行后检查点继续，不重跑 execute");
        check("COMPLETED".equals(saver.get(config).orElseThrow().getState().get(STATUS)), "恢复报告完成");
        System.out.println("PASS: resumed at reporter without executing the completed node again.");
    }

    private static void verifyThreadIsolation() throws Exception {
        MemorySaver saver = new MemorySaver();
        CompiledGraph shared = graph(saver, new AtomicInteger(), false);
        RunnableConfig a = config("task-A");
        RunnableConfig b = config("task-B");
        run(shared, input("task-A", Map.of("warehouseId", "warehouse-A")), a);
        run(shared, input("task-B", Map.of("warehouseId", "warehouse-B")), b);
        check("warehouse-A".equals(parameters(saver.get(a).orElseThrow().getState()).get("warehouseId")), "A 保持自己的参数");
        check("warehouse-B".equals(parameters(saver.get(b).orElseThrow().getState()).get("warehouseId")), "B 保持自己的参数");
        System.out.println("PASS: different threadIds retain different state while sharing graph configuration.");
    }

    private static void run(CompiledGraph graph, Map<String, Object> input, RunnableConfig config) {
        graph.stream(input, config).collectList().block(java.time.Duration.ofSeconds(10));
    }

    private static RunnableConfig config(String taskId) {
        return RunnableConfig.builder().threadId("demo:" + taskId).build();
    }

    private static Map<String, Object> input(String taskId, Map<String, Object> parameters) {
        return Map.of(TASK_ID, taskId, REQUEST, "按仓库查询销售", PARAMETERS, parameters, STATUS, "CREATED");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parameters(Map<String, Object> state) {
        return (Map<String, Object>) state.getOrDefault(PARAMETERS, Map.of());
    }

    private static String warehouse(OverAllState state) {
        return String.valueOf(parameters(state.data()).getOrDefault("warehouseId", ""));
    }

    private static String required(OverAllState state, String key) {
        return state.value(key, String.class).filter(value -> !value.isBlank()).orElseThrow();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
