package com.qiheng.erp.ai.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.service.knowledge.KnowledgeProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 业务知识节点：按当前用户的权限码组装该注入的业务口径。
 *
 * <p>与 {@code DataAccessNode} 的区别：那个节点回答"能查哪些表"，
 * 这个节点回答"这些数怎么算才对"。两者的模块推导同源（权限码前缀），
 * 所以不会出现查得到表却没口径、或给了口径却查不到表的错配。</p>
 *
 * <p><b>它是增强不是依赖</b>：装配失败或用户没有任何可查模块时，写空串继续往下走，
 * 规划流程不因为缺业务口径而中断。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
@Slf4j
public class KnowledgeNode implements NodeAction {

    private final KnowledgeProvider provider;

    public KnowledgeNode(KnowledgeProvider provider) {
        this.provider = provider;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        List<String> permissionCodes = state.value(GraphStateKeys.PERMISSION_CODES, List.of());
        String knowledge = "";
        try {
            knowledge = provider.provide(permissionCodes);
        } catch (RuntimeException error) {
            // 降级：业务口径缺失只是让模型少知道一些约定，不该让整轮规划失败
            log.warn("业务口径装配失败，按空口径继续 permissionCodes={}", permissionCodes, error);
        }
        log.info("业务口径已就绪 权限码={} 口径长度={}", permissionCodes.size(), knowledge.length());
        Map<String, Object> update = new HashMap<>();
        update.put(GraphStateKeys.BUSINESS_KNOWLEDGE, knowledge);
        return update;
    }
}
