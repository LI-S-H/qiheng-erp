package com.qiheng.erp.ai.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.qiheng.erp.ai.constant.GraphStateKeys.*;

/** 执行前由图中断；恢复后消费本轮反馈，再回到 planner。 */
public class HumanFeedbackNode implements NodeAction {

    @Override
    public Map<String, Object> apply(OverAllState state) {
        String answer = state.value(HUMAN_FEEDBACK, "");
        if (answer.isBlank()) {
            throw new IllegalStateException("人工节点缺少待消费回答");
        }

        List<?> questions = state.value(CLARIFICATION_QUESTIONS, List.of());
        if (questions.isEmpty()) {
            throw new IllegalStateException("人工节点缺少对应的澄清问题");
        }

        List<Object> history = new ArrayList<>(state.value(CLARIFICATION_HISTORY, List.of()));
        // 原样保留问题对象及候选项，回答可以包含用户自定义修改，不能仅归档题目文本。
        history.add(Map.of("questions", List.copyOf(questions), "answer", answer.trim()));

        Map<String, Object> update = new HashMap<>();
        update.put(CLARIFICATION_HISTORY, history);
        // 只消费一次当前反馈；上一轮的问题不能被当成新需求再次返回。
        update.put(HUMAN_FEEDBACK, "");
        update.put(CLARIFICATION_QUESTIONS, List.of());
        update.put(PLANNER_OUTPUT, "");
        update.put(NEED_CHECK_RESULT, "");
        update.put(NEED_REVIEW_RESULT, "");
        // 用户补充信息后重新给一次完整修复预算：新的输入值得一次完整的拆解机会。
        update.put(REPAIR_COUNT, 0);
        update.put(REVIEW_COUNT, 0);
        update.put(REPAIR_ERROR, Map.of());
        update.put(REPAIR_ISSUES, List.of());
        return update;
    }
}
