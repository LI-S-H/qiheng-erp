package com.qiheng.erp.ai.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.service.data.registry.StubSchemaProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据范围节点单测：按 State 里的权限码组装 Schema 并写回 State。
 *
 * @author Li
 * @since 2026-10-21
 */
class DataAccessNodeTest {

    private final DataAccessNode node = new DataAccessNode(new StubSchemaProvider());

    @Test
    void schemaIsWrittenToStateWithTableCount() {
        OverAllState state = new OverAllState(Map.of(
                GraphStateKeys.PERMISSION_CODES, List.of("product:query")));

        Map<String, Object> update = node.apply(state);

        @SuppressWarnings("unchecked")
        Map<String, Object> ref = (Map<String, Object>) update.get(GraphStateKeys.AUTHORIZED_SCHEMA_REF);
        assertThat(ref).containsKey("schema");
        assertThat((String) ref.get("schema")).contains("# Table: product");
        assertThat(ref.get("tableCount")).isEqualTo(1);
        assertThat(ref.get("permissionScope")).isEqualTo("SCOPED");
    }

    @Test
    void emptyPermissionScopeIsMarkedAsNone() {
        OverAllState state = new OverAllState(Map.of(
                GraphStateKeys.PERMISSION_CODES, List.of()));

        Map<String, Object> update = node.apply(state);

        @SuppressWarnings("unchecked")
        Map<String, Object> ref = (Map<String, Object>) update.get(GraphStateKeys.AUTHORIZED_SCHEMA_REF);
        assertThat(ref.get("permissionScope")).isEqualTo("NONE");
    }
}
