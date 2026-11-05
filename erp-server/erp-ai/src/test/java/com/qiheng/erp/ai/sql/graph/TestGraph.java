package com.qiheng.erp.ai.sql.graph;

import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;

import java.util.Map;
import java.util.function.Function;

/** 测试用节点桩：将简短函数接到真实图，不参与生产节点装配。 */
final class TestGraph {
    interface Operations {
        Map<String, Object> prepare(Map<String, Object> work);
        Map<String, Object> generate(Map<String, Object> work);
        Map<String, Object> execute(Map<String, Object> work);
        Map<String, Object> inspect(Map<String, Object> work);
    }

    static SqlGraph create(BaseCheckpointSaver saver, Operations operations) throws Exception {
        return new SqlGraph(saver, node(operations::prepare), node(operations::generate),
                node(operations::execute), node(operations::inspect));
    }

    private static NodeAction node(Function<Map<String, Object>, Map<String, Object>> function) {
        return state -> SqlNodeSupport.update(function.apply(SqlNodeSupport.work(state)));
    }
}
