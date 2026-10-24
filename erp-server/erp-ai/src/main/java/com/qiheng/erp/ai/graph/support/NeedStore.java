package com.qiheng.erp.ai.graph.support;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.constant.NeedStatus;
import com.qiheng.erp.ai.domain.dto.needs.Need;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求数组在 State 里的读写：唯一知道"需求 + 状态"怎么存的地方。
 *
 * <p>State 里存的是 JSON 兼容结构（Map），不是领域对象：经过 Redis 检查点
 * 序列化后仍能稳定转回 {@link Need}，前端也能直接读同一份形状。</p>
 *
 * <p>状态由代码节点维护，模型输出协议里没有 status。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
public final class NeedStore {

    /**
     * 一条需求连同它的运行期状态。
     *
     * @param need   需求本体
     * @param status 运行期状态，模型碰不到
     */
    public record StoredNeed(Need need, NeedStatus status) {

        /** 终态需求不再允许被改写。 */
        public boolean terminal() {
            return status == NeedStatus.DONE
                    || status == NeedStatus.SKIPPED
                    || status == NeedStatus.INFEASIBLE
                    || status == NeedStatus.BLOCKED;
        }
    }

    private NeedStore() {
    }

    /**
     * 读取当前需求数组。
     *
     * @param state  图状态
     * @param mapper 序列化器
     * @return 需求数组；没有时返回空列表
     */
    public static List<StoredNeed> read(OverAllState state, ObjectMapper mapper) {
        List<Map<String, Object>> raw = state.value(GraphStateKeys.NEEDS, List.of());
        List<StoredNeed> needs = new ArrayList<>(raw.size());
        for (Map<String, Object> item : raw) {
            NeedStatus status = NeedStatus.PENDING;
            Object rawStatus = item.get("status");
            if (rawStatus != null) {
                status = NeedStatus.valueOf(rawStatus.toString());
            }
            // 状态是代码侧的字段，不在需求协议里；转成需求本体前先摘掉，
            // 否则严格模式下会被当成未知字段拒掉。
            Map<String, Object> body = new LinkedHashMap<>(item);
            body.remove("status");
            needs.add(new StoredNeed(mapper.convertValue(body, Need.class), status));
        }
        return needs;
    }

    /**
     * 需求数组的模型视图：只含需求本体，不含状态。
     *
     * <p>状态是代码侧的运行期信息，不进模型视野——模型既不产生它也不该读它。</p>
     *
     * @param needs  需求数组
     * @param mapper 序列化器
     * @return 需求本体的 JSON 兼容结构
     */
    public static List<Map<String, Object>> toModelView(List<StoredNeed> needs, ObjectMapper mapper) {
        List<Map<String, Object>> views = new ArrayList<>(needs.size());
        for (StoredNeed stored : needs) {
            views.add(new LinkedHashMap<>(mapper.convertValue(stored.need(), Map.class)));
        }
        return views;
    }

    /**
     * 数同时未完成的需求：终态不占名额。
     *
     * @param needs  需求数组
     * @param prefix 需求 id 段前缀（{@code N} 用户需求 / {@code D} 派生需求）
     * @return 未完成且 id 段匹配的需求数
     */
    public static int openCount(List<StoredNeed> needs, String prefix) {
        int count = 0;
        for (StoredNeed stored : needs) {
            if (stored.status().terminal()) {
                continue;
            }
            if (stored.need().id().startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 需求数组写回 State 的 JSON 兼容结构。
     *
     * @param needs  需求数组
     * @param mapper 序列化器
     * @return 可直接放入 State 的结构
     */
    public static List<Map<String, Object>> toMaps(List<StoredNeed> needs, ObjectMapper mapper) {
        List<Map<String, Object>> maps = new ArrayList<>(needs.size());
        for (StoredNeed stored : needs) {
            maps.add(toMap(stored, mapper));
        }
        return maps;
    }

    /**
     * 把一条需求写成 JSON 兼容结构，状态单独一个字段。
     *
     * @param stored 需求与状态
     * @param mapper 序列化器
     * @return 可直接放入 State 的结构
     */
    public static Map<String, Object> toMap(StoredNeed stored, ObjectMapper mapper) {
        Map<String, Object> map = new LinkedHashMap<>(mapper.convertValue(stored.need(), Map.class));
        map.put("status", stored.status().name());
        return map;
    }
}
