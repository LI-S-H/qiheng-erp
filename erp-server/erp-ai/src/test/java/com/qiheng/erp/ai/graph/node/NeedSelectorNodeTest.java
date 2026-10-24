package com.qiheng.erp.ai.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.constant.NeedSelectorResult;
import com.qiheng.erp.ai.constant.NeedStatus;
import com.qiheng.erp.ai.graph.support.NeedStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.qiheng.erp.ai.constant.GraphStateKeys.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 需求选择节点的契约测试。
 *
 * <p>选择规则：第一个 PENDING 且依赖全部进入终态的需求；上游 BLOCKED 时
 * 本需求与下游一并标 BLOCKED；SKIPPED 也算依赖满足。</p>
 */
class NeedSelectorNodeTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NeedSelectorNode node = new NeedSelectorNode(mapper);

    private Map<String, Object> need(String id, List<String> depends, NeedStatus status) {
        return need(id, depends, List.of(), status);
    }

    private Map<String, Object> need(String id, List<String> depends, List<String> itemDepends, NeedStatus status) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", id + "-E1");
        item.put("label", "数据");
        item.put("instruction", "查数据");
        item.put("source", "SQL");
        item.put("depends", itemDepends);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("intent", "DATA");
        map.put("need", "需求 " + id);
        map.put("depends", depends);
        map.put("condition", null);
        map.put("evidence_plan", List.of(item));
        map.put("status", status.name());
        return map;
    }

    private OverAllState stateOf(Map<String, Object>... needs) {
        return new OverAllState(Map.of(NEEDS, List.of(needs)));
    }

    @Test
    void firstPendingNeedIsSelected() {
        OverAllState state = stateOf(need("N1", List.of(), NeedStatus.PENDING),
                need("N2", List.of(), NeedStatus.PENDING));

        Map<String, Object> update = node.apply(state);

        assertThat(update.get(CURRENT_NEED_ID)).isEqualTo("N1");
        assertThat(update.get(NEED_SELECTOR_RESULT)).isEqualTo(NeedSelectorResult.SELECTED.name());
    }

    @Test
    void evidenceReferenceIsTreatedAsDependency() {
        // N2 的证据项引用了 N1 的证据，即使需求级 depends 是空的，也必须先做 N1
        OverAllState state = stateOf(
                need("N2", List.of(), List.of("N1-E1"), NeedStatus.PENDING),
                need("N1", List.of(), NeedStatus.PENDING));

        Map<String, Object> update = node.apply(state);

        assertThat(update.get(CURRENT_NEED_ID)).isEqualTo("N1");
    }

    @Test
    void needWithUnfinishedDependencyIsSkipped() {
        OverAllState state = stateOf(need("N1", List.of(), NeedStatus.PENDING),
                need("N2", List.of("N1"), NeedStatus.PENDING));

        Map<String, Object> update = node.apply(state);

        assertThat(update.get(CURRENT_NEED_ID)).isEqualTo("N1");
    }

    @Test
    void dependencyInTerminalStateUnblocksTheNextNeed() {
        OverAllState state = stateOf(need("N1", List.of(), NeedStatus.DONE),
                need("N2", List.of("N1"), NeedStatus.PENDING));

        Map<String, Object> update = node.apply(state);

        assertThat(update.get(CURRENT_NEED_ID)).isEqualTo("N2");
    }

    @Test
    void skippedDependencyStillCountsAsSatisfied() {
        OverAllState state = stateOf(need("N1", List.of(), NeedStatus.SKIPPED),
                need("N2", List.of("N1"), NeedStatus.PENDING));

        Map<String, Object> update = node.apply(state);

        assertThat(update.get(CURRENT_NEED_ID)).isEqualTo("N2");
    }

    @Test
    void blockedDependencyMarksTheNeedAndItsDownstreamBlocked() {
        OverAllState state = stateOf(need("N1", List.of(), NeedStatus.BLOCKED),
                need("N2", List.of("N1"), NeedStatus.PENDING),
                need("N3", List.of("N2"), NeedStatus.PENDING),
                need("N4", List.of(), NeedStatus.PENDING));

        Map<String, Object> update = node.apply(state);

        assertThat(update.get(CURRENT_NEED_ID)).isEqualTo("N4");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> needs = (List<Map<String, Object>>) update.get(NEEDS);
        assertThat(statusOf(needs, "N2")).isEqualTo(NeedStatus.BLOCKED.name());
        assertThat(statusOf(needs, "N3")).isEqualTo(NeedStatus.BLOCKED.name());
        assertThat(statusOf(needs, "N1")).isEqualTo(NeedStatus.BLOCKED.name());
    }

    @Test
    void allTerminalMeansNothingToDo() {
        OverAllState state = stateOf(need("N1", List.of(), NeedStatus.DONE),
                need("N2", List.of(), NeedStatus.SKIPPED));

        Map<String, Object> update = node.apply(state);

        assertThat(update.get(CURRENT_NEED_ID)).isEqualTo("");
        assertThat(update.get(NEED_SELECTOR_RESULT)).isEqualTo(NeedSelectorResult.ALL_TERMINAL.name());
    }

    @Test
    void unchangedStatusesAreNotWrittenBack() {
        OverAllState state = stateOf(need("N1", List.of(), NeedStatus.PENDING));

        Map<String, Object> update = node.apply(state);

        assertThat(update).doesNotContainKey(NEEDS);
    }

    private String statusOf(List<Map<String, Object>> needs, String id) {
        for (Map<String, Object> need : needs) {
            if (id.equals(need.get("id"))) {
                return String.valueOf(need.get("status"));
            }
        }
        return null;
    }
}
