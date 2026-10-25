package com.qiheng.erp.ai.service.data.schema;

import com.qiheng.erp.ai.domain.entity.AiDataTable;
import com.qiheng.erp.ai.domain.entity.AiDataTableColumn;
import com.qiheng.erp.ai.domain.entity.AiDataTableRelation;
import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;
import com.qiheng.erp.ai.mapper.AiDataTableColumnMapper;
import com.qiheng.erp.ai.mapper.AiDataTableMapper;
import com.qiheng.erp.ai.mapper.AiDataTableRelationMapper;
import com.qiheng.erp.ai.service.data.scope.DataScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 受控 Schema 提供者：按当前用户的权限码算出可见表，组装成给模型的 Schema 文本
 * 与给代码的结构化清单。
 *
 * <p>可见范围的推导分四步：</p>
 * <ol>
 *   <li>权限码 → 可见模块（{@link DataScope}：动作必须是 query 或 manage）；</li>
 *   <li>模块 → 已登记且启用的表；</li>
 *   <li>表 → 要下发给模型的列（{@code status=1} 挡掉噪声列与敏感列）；</li>
 *   <li>关系 → <b>只保留两端表都可见的</b>。采购员看不到 {@code purchase_order} 时，
 *       {@code purchase_order_item → purchase_order} 这条关系也不给，
 *       否则模型会去 JOIN 一个 Schema 里不存在的表。</li>
 * </ol>
 *
 * @author Li
 * @since 2026-10-21
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthorizedSchemaProvider implements SchemaProvider {

    /** 软删除列名：有这一列的表，查询必须带过滤条件。 */
    private static final String LOGICAL_DELETE_COLUMN = "deleted";

    private final AiDataTableMapper tableMapper;
    private final AiDataTableColumnMapper columnMapper;
    private final AiDataTableRelationMapper relationMapper;
    private final SchemaRenderer renderer;
    private final SchemaCache cache;

    @Override
    public AuthorizedSchemaSnapshot provideSnapshot(List<String> permissionCodes) {
        DataScope scope = DataScope.of(permissionCodes);
        if (scope.isEmpty()) {
            // 该用户没有任何可查模块：空快照是合法结果，不报错也不缓存
            return AuthorizedSchemaSnapshot.empty();
        }
        String key = scope.admin() ? DataScope.ADMIN_CODE : String.join(",", scope.modules());
        return cache.get(key).orElseGet(() -> {
            AuthorizedSchemaSnapshot snapshot = assemble(scope);
            cache.put(key, snapshot);
            return snapshot;
        });
    }

    /**
     * 组装可见 Schema 快照。
     */
    private AuthorizedSchemaSnapshot assemble(DataScope scope) {
        List<AiDataTable> tables = scope.admin()
                ? tableMapper.selectEnabledAll() // 超管 → 全部启用的表
                : tableMapper.selectEnabledByModules(List.copyOf(scope.modules())); // 普通用户 → 按模块筛选
        if (tables.isEmpty()) {
            return AuthorizedSchemaSnapshot.empty();
        }
        List<String> tableNames = tables.stream().map(AiDataTable::getTableName).toList();
        List<AiDataTableColumn> visibleColumns = columnMapper.selectEnabledByTables(tableNames);
        List<AiDataTableRelation> relations = relationMapper.selectEnabled();
        String schemaText = renderer.render(tables, renderer.groupByTable(visibleColumns), relations);

        // 结构化清单：给 SQL 守卫用。列名取下发给模型的那批——被屏蔽的列模型看不到，
        // 守卫也不该放行，屏蔽才有意义。
        Map<String, List<AuthorizedSchemaSnapshot.Column>> columns = new LinkedHashMap<>();
        for (AiDataTableColumn column : visibleColumns) {
            columns.computeIfAbsent(column.getTableName(), ignored -> new ArrayList<>())
                    .add(new AuthorizedSchemaSnapshot.Column(column.getColumnName(),
                            Integer.valueOf(1).equals(column.getNullable()),
                            Integer.valueOf(1).equals(column.getPrimaryKey())));
        }
        // 软删除列与主键是表结构事实：与这一列是否下发给模型无关
        Set<String> softDeleteTables = new LinkedHashSet<>();
        for (AiDataTableColumn column : columnMapper.selectByTablesAndColumn(tableNames, LOGICAL_DELETE_COLUMN)) {
            softDeleteTables.add(column.getTableName());
        }
        Map<String, String> primaryKeys = new LinkedHashMap<>();
        for (AiDataTableColumn column : columnMapper.selectPrimaryKeys(tableNames)) {
            primaryKeys.putIfAbsent(column.getTableName(), column.getColumnName());
        }
        List<AuthorizedSchemaSnapshot.Table> tableMetas = tables.stream()
                .map(table -> new AuthorizedSchemaSnapshot.Table(table.getTableName(), table.getBusinessName(),
                        table.getModuleCode(), softDeleteTables.contains(table.getTableName()),
                        primaryKeys.get(table.getTableName())))
                .toList();
        return AuthorizedSchemaSnapshot.of(schemaText, tableMetas, columns);
    }
}
