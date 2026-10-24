package com.qiheng.erp.ai.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.constant.NeedSelectorResult;
import com.qiheng.erp.ai.constant.NeedStatus;
import com.qiheng.erp.ai.domain.dto.needs.Need;
import com.qiheng.erp.ai.graph.support.NeedDependencies;
import com.qiheng.erp.ai.graph.support.NeedStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 需求选择节点：决定这一刻该做哪条需求。
 *
 * <p>选择规则只有一条：第一个 PENDING 且依赖全部进入终态的需求。
 * 依赖里出现 BLOCKED 时，这条需求与它的下游一并标 BLOCKED；
 * 依赖全部 SKIPPED 也算满足，否则条件不成立的需求会把下游永久卡住。</p>
 *
 * <p>依赖读的是<b>有效依赖图</b>（{@link NeedDependencies}），不只是模型写的
 * {@code depends}：证据项引用了别的需求，同样算要等它。</p>
 *
 * <p><b>本批次的已知限制</b>：条件判定要用证据链求值，而证据链在下一批才接入。
 * 在此之前带 condition 的需求一律视为条件成立（暂不跳过），避免把还没机会
 * 求值的需求误标成 SKIPPED。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
@Slf4j
public class NeedSelectorNode implements NodeAction {

    private final ObjectMapper mapper;

    public NeedSelectorNode(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        // 1. 读取所有需求
        List<NeedStore.StoredNeed> needs = NeedStore.read(state, mapper);
        // 需求id → 需求
        Map<String, Need> bodies = new LinkedHashMap<>();
        // 需求id → 需求状态
        Map<String, NeedStatus> statuses = new LinkedHashMap<>();
        // 2. 读取所有需求的状态
        for (NeedStore.StoredNeed stored : needs) {
            bodies.put(stored.need().id(), stored.need());
            statuses.put(stored.need().id(), stored.status());
        }
        // 有效依赖图：需求级 depends + 证据项引用 + condition 引用(需求id -> 依赖的需求id)
        Map<String, Set<String>> upstream = NeedDependencies.upstream(new ArrayList<>(bodies.values()));

        // 遍历 PENDING 需求，选出当前要做的
        Need selected = null;
        for (NeedStore.StoredNeed stored : needs) {
            if (stored.status() != NeedStatus.PENDING) {
                continue;
            }
            // 检查上游是否有 BLOCKED
            if (hasBlockedDependency(stored.need().id(), upstream, statuses)) {
                statuses.put(stored.need().id(), NeedStatus.BLOCKED);
                // 标记下游需求为 BLOCKED
                markDownstreamBlocked(stored.need().id(), upstream, statuses);
                continue;
            }
            // 检查依赖是否全部进入终态(DONE、SKIPPED、INFEASIBLE)
            if (!dependenciesSatisfied(stored.need().id(), upstream, statuses)) {
                continue;
            }
            // 选第一个满足的
            selected = stored.need();
            break;
        }
        // 状态有变化则写回状态
        Map<String, Object> update = new HashMap<>();
        if (statusesChanged(statuses, needs)) {
            List<NeedStore.StoredNeed> rewritten = new ArrayList<>(needs.size());
            for (NeedStore.StoredNeed stored : needs) {
                NeedStatus status = statuses.getOrDefault(stored.need().id(), stored.status());
                rewritten.add(new NeedStore.StoredNeed(stored.need(), status));
            }
            update.put(GraphStateKeys.NEEDS, NeedStore.toMaps(rewritten, mapper));
        }
        // 没有可执行需求, 则返回结束状态
        if (selected == null) {
            update.put(GraphStateKeys.CURRENT_NEED_ID, "");
            update.put(GraphStateKeys.NEED_SELECTOR_RESULT, NeedSelectorResult.ALL_TERMINAL.name());
            log.info("没有可执行需求 需求总数={}", needs.size());
            return update;
        }
        // 选中需求
        update.put(GraphStateKeys.CURRENT_NEED_ID, selected.id());
        update.put(GraphStateKeys.NEED_SELECTOR_RESULT, NeedSelectorResult.SELECTED.name());
        log.info("选中需求 needId={} intent={}", selected.id(), selected.intent());
        return update;
    }

    /**
     * 上游有没有处于 BLOCKED 的需求。
     */
    private boolean hasBlockedDependency(String needId, Map<String, Set<String>> upstream,
                                         Map<String, NeedStatus> statuses) {
        for (String dependency : upstream.getOrDefault(needId, Set.of())) {
            if (statuses.get(dependency) == NeedStatus.BLOCKED) {
                return true;
            }
        }
        return false;
    }

    /**
     * 依赖是否全部进入终态。
     *
     * <p>DONE、SKIPPED、INFEASIBLE 算满足；BLOCKED 不算（由调用方先处理）；
     * PENDING 与 RUNNING 显然不算。</p>
     */
    private boolean dependenciesSatisfied(String needId, Map<String, Set<String>> upstream,
                                          Map<String, NeedStatus> statuses) {
        for (String dependency : upstream.getOrDefault(needId, Set.of())) {
            NeedStatus status = statuses.get(dependency);
            if (status == null || status == NeedStatus.PENDING || status == NeedStatus.RUNNING
                    || status == NeedStatus.BLOCKED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 上游被阻塞时，下游一并标 BLOCKED。
     */
    private void markDownstreamBlocked(String blockedId, Map<String, Set<String>> upstream,
                                       Map<String, NeedStatus> statuses) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, NeedStatus> entry : statuses.entrySet()) {
                if (entry.getValue() != NeedStatus.PENDING) {
                    continue;
                }
                for (String dependency : upstream.getOrDefault(entry.getKey(), Set.of())) {
                    if (dependency.equals(blockedId) || statuses.get(dependency) == NeedStatus.BLOCKED) {
                        entry.setValue(NeedStatus.BLOCKED);
                        changed = true;
                        break;
                    }
                }
            }
        }
    }

    /**
     * 判断状态是否发生变化，避免无谓地把需求数组写回 State。
     */
    private boolean statusesChanged(Map<String, NeedStatus> statuses, List<NeedStore.StoredNeed> needs) {
        for (NeedStore.StoredNeed stored : needs) {
            NeedStatus current = statuses.get(stored.need().id());
            if (current != null && current != stored.status()) {
                return true;
            }
        }
        return false;
    }
}
