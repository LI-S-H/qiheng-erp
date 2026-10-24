package com.qiheng.erp.ai.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.service.knowledge.KnowledgeProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.qiheng.erp.ai.constant.GraphStateKeys.PERMISSION_CODES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 业务口径节点的契约测试。
 *
 * <p>它是增强不是依赖：装配失败要降级为空继续，不能让整轮规划挂掉。</p>
 */
class KnowledgeNodeTest {

    @Test
    void knowledgeIsWrittenIntoState() {
        KnowledgeProvider provider = mock(KnowledgeProvider.class);
        when(provider.provide(any())).thenReturn("业务口径正文");

        Map<String, Object> update = new KnowledgeNode(provider).apply(
                new OverAllState(Map.of(PERMISSION_CODES, List.of("purchase:query"))));

        assertThat(update.get(GraphStateKeys.BUSINESS_KNOWLEDGE)).isEqualTo("业务口径正文");
    }

    @Test
    void failureDegradesToEmptyInsteadOfBreakingTheRun() {
        KnowledgeProvider provider = mock(KnowledgeProvider.class);
        when(provider.provide(any())).thenThrow(new IllegalStateException("装配失败"));

        Map<String, Object> update = new KnowledgeNode(provider).apply(
                new OverAllState(Map.of(PERMISSION_CODES, List.of("purchase:query"))));

        assertThat(update.get(GraphStateKeys.BUSINESS_KNOWLEDGE)).isEqualTo("");
    }

    @Test
    void noPermissionCodesYieldEmptyKnowledge() {
        KnowledgeProvider provider = mock(KnowledgeProvider.class);
        when(provider.provide(any())).thenReturn("");

        Map<String, Object> update = new KnowledgeNode(provider).apply(new OverAllState(Map.of()));

        assertThat(update.get(GraphStateKeys.BUSINESS_KNOWLEDGE)).isEqualTo("");
    }
}
