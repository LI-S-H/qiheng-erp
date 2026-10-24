package com.qiheng.erp.ai.graph.support;

import com.qiheng.erp.ai.domain.dto.needs.EvidenceItem;
import com.qiheng.erp.ai.domain.dto.needs.Need;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 需求之间的有效依赖图。
 *
 * <p><b>引用即依赖</b>：需求级 {@code depends}、证据项跨需求引用、{@code condition}
 * 引用的证据项，三处并成一张图。查环与选需求的排序都只认这张图——模型漏写
 * {@code depends} 不会让依赖消失。</p>
 *
 * <p>证据项 id 全局唯一，所以"引用了哪一项"可以直接换算成"要等哪条需求"。
 * 指向不存在证据项的引用不加边：那是结构校验的职责，本类只负责建图。</p>
 *
 * @author Li
 * @since 2026-10-23
 */
public final class NeedDependencies {

    /** 条件开关里证据项引用的前缀，形如 {@code evidence.N1-E5}。 */
    private static final String EVIDENCE_PREFIX = "evidence.";

    private NeedDependencies() {
    }

    /**
     * 算每条需求的有效上游。
     *
     * @param needs 合并后的需求
     * @return 需求 id → 它要等的需求 id（去重、不含自身）
     */
    public static Map<String, Set<String>> upstream(List<Need> needs) {
        // 先建 itemOwner：证据项id → 所属需求id
        Map<String, String> itemOwner = new HashMap<>();
        for (Need need : needs) {
            for (EvidenceItem item : need.evidencePlan()) {
                itemOwner.put(item.id(), need.id());
            }
        }
        // 再遍历每条需求，收集三处来源
        Map<String, Set<String>> upstream = new LinkedHashMap<>();
        for (Need need : needs) {
            Set<String> depends = new LinkedHashSet<>(need.depends()); // 1. 需求级依赖
            for (EvidenceItem item : need.evidencePlan()) {
                // 2. 证据项引用的证据项，要等的也是需求
                for (String dependency : item.depends()) {
                    String owner = itemOwner.get(dependency);
                    if (owner != null) {
                        depends.add(owner);
                    }
                }
            }
            // 3. 条件开关引用的需求
            if (need.condition() != null && need.condition().left().startsWith(EVIDENCE_PREFIX)) {
                String owner = itemOwner.get(need.condition().left().substring(EVIDENCE_PREFIX.length()));
                if (owner != null) {
                    depends.add(owner);
                }
            }
            // 去掉自环
            depends.remove(need.id());
            upstream.put(need.id(), depends);
        }
        return upstream;
    }

    /**
     * 在有效依赖图上找环。
     *
     * @param upstream {@link #upstream(List)} 的结果
     * @return 空串表示无环；否则返回形如 {@code N1 → N2 → N1} 的环
     */
    public static String findCycle(Map<String, Set<String>> upstream) {
        Set<String> visited = new HashSet<>();
        Set<String> onPath = new LinkedHashSet<>();
        Deque<String> path = new ArrayDeque<>();
        for (String id : upstream.keySet()) {
            String cycle = visit(id, upstream, visited, onPath, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        return "";
    }

    /**
     * 找环的一步：走到已经在当前路径上的节点，就把路径展开成环。
     *
     * <p>指向不存在的需求时跳过——那一条由需求依赖检查单独报错。</p>
     */
    private static String visit(String id, Map<String, Set<String>> upstream, Set<String> visited,
                                Set<String> onPath, Deque<String> path) {
        if (onPath.contains(id)) {
            List<String> ring = new ArrayList<>(path);
            Collections.reverse(ring);
            return String.join(" → ", ring) + " → " + id;
        }
        if (!visited.add(id)) {
            return "";
        }
        onPath.add(id);
        path.push(id);
        for (String dependency : upstream.getOrDefault(id, Set.of())) {
            if (!upstream.containsKey(dependency)) {
                continue;
            }
            String cycle = visit(dependency, upstream, visited, onPath, path);
            if (!cycle.isEmpty()) {
                return cycle;
            }
        }
        path.pop();
        onPath.remove(id);
        return "";
    }
}