package com.qiheng.erp.ai.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.qiheng.erp.ai.constant.NeedCheckResult;
import com.qiheng.erp.ai.constant.NeedReviewResult;
import com.qiheng.erp.ai.constant.NeedSelectorResult;
import com.qiheng.erp.ai.graph.node.DataAccessNode;
import com.qiheng.erp.ai.graph.node.HumanFeedbackNode;
import com.qiheng.erp.ai.graph.node.KnowledgeNode;
import com.qiheng.erp.ai.graph.node.NeedCheckNode;
import com.qiheng.erp.ai.graph.node.NeedReviewNode;
import com.qiheng.erp.ai.graph.node.NeedSelectorNode;
import com.qiheng.erp.ai.graph.node.PlannerNode;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncEdgeAction.edge_async;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.qiheng.erp.ai.constant.GraphStateKeys.AUTHORIZED_SCHEMA_REF;
import static com.qiheng.erp.ai.constant.GraphStateKeys.BUSINESS_KNOWLEDGE;
import static com.qiheng.erp.ai.constant.GraphStateKeys.CLARIFICATION_HISTORY;
import static com.qiheng.erp.ai.constant.GraphStateKeys.CLARIFICATION_QUESTIONS;
import static com.qiheng.erp.ai.constant.GraphStateKeys.CONFIRMED_PROPOSALS;
import static com.qiheng.erp.ai.constant.GraphStateKeys.CONVERSATION_ID;
import static com.qiheng.erp.ai.constant.GraphStateKeys.CURRENT_NEED_ID;
import static com.qiheng.erp.ai.constant.GraphStateKeys.DATA_ACCESS_NODE;
import static com.qiheng.erp.ai.constant.GraphStateKeys.HUMAN_FEEDBACK;
import static com.qiheng.erp.ai.constant.GraphStateKeys.HUMAN_FEEDBACK_NODE;
import static com.qiheng.erp.ai.constant.GraphStateKeys.KNOWLEDGE_NODE;
import static com.qiheng.erp.ai.constant.GraphStateKeys.NEEDS;
import static com.qiheng.erp.ai.constant.GraphStateKeys.NEED_CHECK_NODE;
import static com.qiheng.erp.ai.constant.GraphStateKeys.NEED_CHECK_RESULT;
import static com.qiheng.erp.ai.constant.GraphStateKeys.NEED_REVIEW_ERROR;
import static com.qiheng.erp.ai.constant.GraphStateKeys.NEED_REVIEW_NODE;
import static com.qiheng.erp.ai.constant.GraphStateKeys.NEED_REVIEW_OUTPUT;
import static com.qiheng.erp.ai.constant.GraphStateKeys.NEED_REVIEW_RESULT;
import static com.qiheng.erp.ai.constant.GraphStateKeys.NEED_SELECTOR_NODE;
import static com.qiheng.erp.ai.constant.GraphStateKeys.NEED_SELECTOR_RESULT;
import static com.qiheng.erp.ai.constant.GraphStateKeys.PAUSE_ID;
import static com.qiheng.erp.ai.constant.GraphStateKeys.PERMISSION_CODES;
import static com.qiheng.erp.ai.constant.GraphStateKeys.PLANNER_MODE;
import static com.qiheng.erp.ai.constant.GraphStateKeys.PLANNER_NODE;
import static com.qiheng.erp.ai.constant.GraphStateKeys.PLANNER_OUTPUT;
import static com.qiheng.erp.ai.constant.GraphStateKeys.PROPOSAL_OUTPUT;
import static com.qiheng.erp.ai.constant.GraphStateKeys.PUBLISHED_ARTIFACT;
import static com.qiheng.erp.ai.constant.GraphStateKeys.REPAIR_COUNT;
import static com.qiheng.erp.ai.constant.GraphStateKeys.REPAIR_ERROR;
import static com.qiheng.erp.ai.constant.GraphStateKeys.REPAIR_ISSUES;
import static com.qiheng.erp.ai.constant.GraphStateKeys.REFINE_GAPS;
import static com.qiheng.erp.ai.constant.GraphStateKeys.REQUEST_ID;
import static com.qiheng.erp.ai.constant.GraphStateKeys.ROUND_NO;
import static com.qiheng.erp.ai.constant.GraphStateKeys.REVIEW_COUNT;
import static com.qiheng.erp.ai.constant.GraphStateKeys.USER_ID;
import static com.qiheng.erp.ai.constant.GraphStateKeys.USER_REQUEST;

/**
 * 图拓扑：节点、边、State 键策略与编译参数。
 *
 * <p>图长什么样只由本类决定，与 Spring 装配无关。改图结构（加节点、调整边、
 * 改路由）只动这里；改执行流程（加锁、收尾、重试）只动
 * {@code graph.runtime} 包下的编排类。两者不混在同一个类里。</p>
 *
 * <p>需求层的链路是 planner → need_check → need_review → need_selector。
 * 结构校验与业务审阅共用一份修复预算：结构错或业务漏查都回 planner，
 * 只有审阅自身的输出格式不合规才回审阅节点重问。</p>
 *
 * <p>测试与生产复用同一拓扑：测试只替换模型响应，不替换暂停恢复机制。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
public final class GraphTopology {

    private GraphTopology() {
    }

    /**
     * 编译出可执行的图。
     *
     * @param planner          需求拆解节点
     * @param needCheck        需求结构校验节点
     * @param needReview       业务完整性审阅节点
     * @param needSelector     需求选择节点
     * @param dataAccess       数据范围节点
     * @param knowledge        业务口径节点
     * @param saver            检查点存储
     * @param maxFormatRetries 修复上限，参与递归深度计算
     * @return 编译后的图
     * @throws Exception 框架编译异常
     */
    public static CompiledGraph compile(PlannerNode planner, NeedCheckNode needCheck,
                                         NeedReviewNode needReview, NeedSelectorNode needSelector,
                                         DataAccessNode dataAccess, KnowledgeNode knowledge,
                                         RedisSaver saver, int maxFormatRetries) throws Exception {
        StateGraph graph = new StateGraph("erp-agent", GraphTopology::keyStrategies);

        graph.addNode(DATA_ACCESS_NODE, node_async(dataAccess))
                .addNode(KNOWLEDGE_NODE, node_async(knowledge))
                .addNode(PLANNER_NODE, node_async(planner))
                .addNode(NEED_CHECK_NODE, node_async(needCheck))
                .addNode(NEED_REVIEW_NODE, node_async(needReview))
                .addNode(NEED_SELECTOR_NODE, node_async(needSelector))
                .addNode(HUMAN_FEEDBACK_NODE, node_async(new HumanFeedbackNode()))
                .addEdge(START, DATA_ACCESS_NODE)
                .addEdge(DATA_ACCESS_NODE, KNOWLEDGE_NODE)
                .addEdge(KNOWLEDGE_NODE, PLANNER_NODE)
                // 规划完直接进结构校验；请求澄清的分支由 need_check 判定并挂起
                .addEdge(PLANNER_NODE, NEED_CHECK_NODE)
                // 结构校验：合规进审阅，需要澄清暂停，其余回 planner 修复或收场
                .addConditionalEdges(NEED_CHECK_NODE,
                        edge_async(state -> state.value(NEED_CHECK_RESULT, "")),
                        Map.of(
                                NeedCheckResult.READY.name(), NEED_REVIEW_NODE,
                                NeedCheckResult.RETRY_FORMAT.name(), PLANNER_NODE,
                                NeedCheckResult.WAITING_HUMAN.name(), HUMAN_FEEDBACK_NODE,
                                NeedCheckResult.FORMAT_RETRY_EXHAUSTED.name(), END))
                // 业务审阅：合规进需求选择，有问题回 planner，自身格式错回本节点
                .addConditionalEdges(NEED_REVIEW_NODE,
                        edge_async(state -> state.value(NEED_REVIEW_RESULT, "")),
                        Map.of(
                                NeedReviewResult.OK.name(), NEED_SELECTOR_NODE,
                                NeedReviewResult.ISSUES.name(), PLANNER_NODE,
                                NeedReviewResult.RETRY_FORMAT.name(), NEED_REVIEW_NODE,
                                NeedReviewResult.FORMAT_RETRY_EXHAUSTED.name(), END))
                // 需求选择：选出当前需求进入完成判定（下一批接入），全部终态进入报告
                .addConditionalEdges(NEED_SELECTOR_NODE,
                        edge_async(state -> state.value(NEED_SELECTOR_RESULT, "")),
                        Map.of(
                                NeedSelectorResult.SELECTED.name(), END,
                                NeedSelectorResult.ALL_TERMINAL.name(), END))
                .addEdge(HUMAN_FEEDBACK_NODE, PLANNER_NODE);

        return graph.compile(CompileConfig.builder()
                .saverConfig(SaverConfig.builder().register(saver).build())
                .interruptBefore(HUMAN_FEEDBACK_NODE)
                .releaseThread(false)
                // 回边有两条（结构错回 planner、业务漏查回 planner）。一次修复要占用
                // planner → need_check → need_review 三跳，澄清回环再占一跳，
                // 深度必须留够，否则方案链路一走到展开轮就栈溢出。
                .recursionLimit(Math.max(50, (maxFormatRetries + 1) * 6 + 12))
                .build());
    }

    /**
     * State 键策略：全部 REPLACE。
     *
     * <p>问答列表由 Human 基于旧值生成完整新列表，避免 AppendStrategy 导致重复追加。</p>
     */
    private static Map<String, KeyStrategy> keyStrategies() {
        Map<String, KeyStrategy> strategies = new HashMap<>();
        for (String key : List.of(USER_ID, CONVERSATION_ID, REQUEST_ID, USER_REQUEST, CLARIFICATION_HISTORY,
                CLARIFICATION_QUESTIONS, PLANNER_OUTPUT, PROPOSAL_OUTPUT, PLANNER_MODE, NEEDS, NEED_CHECK_RESULT,
                NEED_REVIEW_RESULT, NEED_SELECTOR_RESULT, NEED_REVIEW_ERROR, NEED_REVIEW_OUTPUT,
                REPAIR_COUNT, REVIEW_COUNT, REPAIR_ERROR, REPAIR_ISSUES, CURRENT_NEED_ID, CONFIRMED_PROPOSALS,
                BUSINESS_KNOWLEDGE,
                ROUND_NO, REFINE_GAPS,
                HUMAN_FEEDBACK, PAUSE_ID, PUBLISHED_ARTIFACT, PERMISSION_CODES, AUTHORIZED_SCHEMA_REF)) {
            strategies.put(key, new ReplaceStrategy());
        }
        return strategies;
    }
}
