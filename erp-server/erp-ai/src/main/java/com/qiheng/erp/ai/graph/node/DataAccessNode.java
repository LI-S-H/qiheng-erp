package com.qiheng.erp.ai.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;
import com.qiheng.erp.ai.service.data.schema.SchemaProvider;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据范围节点：按当前用户的权限码组装可见 Schema，写进 State 供后续节点使用。
 *
 * <p>不调用模型，只做可见性计算与文本组装，所以是纯代码节点。</p>
 *
 * <p>写进 State 的是一份快照的两副面孔：{@code schema} 文本给模型看，
 * {@code tables} / {@code columns} 结构化清单给 SQL 守卫看。两者同源，
 * 不会出现"模型看到的表"与"守卫放行的表"不是同一批。</p>
 *
 * <p>Schema 固定在一轮任务内：任务暂停几天后恢复，看到的仍是当初那份，
 * 不会因为中途改了权限而让已经生成的 SQL 与恢复后的可见范围对不上。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
@Slf4j
public class DataAccessNode implements NodeAction {

    private final SchemaProvider schemaProvider;

    public DataAccessNode(SchemaProvider schemaProvider) {
        this.schemaProvider = schemaProvider;
    }

    @Override
    public Map<String, Object> apply(OverAllState state) {
        List<String> permissionCodes = state.value(GraphStateKeys.PERMISSION_CODES, List.of());
        AuthorizedSchemaSnapshot snapshot = schemaProvider.provideSnapshot(permissionCodes);
        Map<String, Object> reference = new LinkedHashMap<>(snapshot.toMap());
        reference.put("permissionScope", permissionCodes.isEmpty() ? "NONE" : "SCOPED");
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(GraphStateKeys.AUTHORIZED_SCHEMA_REF, reference);
        log.info("数据范围已就绪 权限码={} 可见表数={}", permissionCodes.size(), snapshot.tables().size());
        return update;
    }
}
