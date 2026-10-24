package com.qiheng.erp.ai.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.qiheng.erp.ai.constant.GraphStateKeys;
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
 * <p>Schema 文本固定在一轮任务内：任务暂停几天后恢复，看到的仍是当初那份，
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
        String schema = schemaProvider.provide(permissionCodes);
        Map<String, Object> update = new LinkedHashMap<>();
        update.put(GraphStateKeys.AUTHORIZED_SCHEMA_REF, Map.of(
                "schema", schema,
                "tableCount", countTables(schema),
                "permissionScope", permissionCodes.isEmpty() ? "NONE" : "SCOPED"));
        log.info("数据范围已就绪 权限码={} 可见表数={}", permissionCodes.size(), countTables(schema));
        return update;
    }

    /**
     * 统计 Schema 里的表数量，用于观测与日志。
     *
     * @param schema Schema 文本
     * @return 表数量
     */
    private int countTables(String schema) {
        if (schema == null || schema.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (String line : schema.split("\n")) {
            if (line.startsWith("# Table: ")) {
                count++;
            }
        }
        return count;
    }
}
