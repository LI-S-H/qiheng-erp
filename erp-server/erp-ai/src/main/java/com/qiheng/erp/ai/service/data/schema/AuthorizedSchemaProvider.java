package com.qiheng.erp.ai.service.data.schema;

import com.qiheng.erp.ai.domain.entity.AiDataTable;
import com.qiheng.erp.ai.domain.entity.AiDataTableColumn;
import com.qiheng.erp.ai.domain.entity.AiDataTableRelation;
import com.qiheng.erp.ai.mapper.AiDataTableColumnMapper;
import com.qiheng.erp.ai.mapper.AiDataTableMapper;
import com.qiheng.erp.ai.mapper.AiDataTableRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 受控 Schema 提供者：按当前用户的权限码算出可见表，组装成给模型的 Schema 文本。
 *
 * <p>可见范围的推导分四步：</p>
 * <ol>
 *   <li>权限码 → 模块前缀（{@code product:query} → {@code product}；{@code "*"} 是超管）；</li>
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

    /** 超管权限码，可见全部已启用的表。 */
    private static final String ADMIN_CODE = "*";

    private final AiDataTableMapper tableMapper;
    private final AiDataTableColumnMapper columnMapper;
    private final AiDataTableRelationMapper relationMapper;
    private final SchemaRenderer renderer;
    private final SchemaCache cache;

    @Override
    public String provide(List<String> permissionCodes) {
        // 解析权限码，得到可见范围
        Scope scope = resolveScope(permissionCodes);
        if (!scope.admin() && scope.modules().isEmpty()) {
            // 该用户没有任何可查模块：空 Schema 是合法结果，不报错也不缓存
            return "";
        }
        String key = scope.admin() ? ADMIN_CODE : String.join(",", scope.modules());
        return cache.get(key).orElseGet(() -> {
            String schema = assemble(scope);
            cache.put(key, schema);
            return schema;
        });
    }

    /**
     * 组装可见 Schema 文本。
     */
    private String assemble(Scope scope) {
        List<AiDataTable> tables = scope.admin()
                ? tableMapper.selectEnabledAll() // 超管 → selectEnabledAll()：所有启用的表
                : tableMapper.selectEnabledByModules(List.copyOf(scope.modules())); // 普通用户 → selectEnabledByModules()：根据模块筛选
        if (tables.isEmpty()) {
            return "";
        }
        List<String> tableNames = tables.stream().map(AiDataTable::getTableName).toList();
        List<AiDataTableColumn> columns = columnMapper.selectEnabledByTables(tableNames);
        List<AiDataTableRelation> relations = relationMapper.selectEnabled();
        return renderer.render(tables, renderer.groupByTable(columns), relations);
    }

    /**
     * 权限码 → 可见范围。
     *
     * <p>权限码格式是 {@code <模块>:<动作>}，取冒号前的模块前缀。超管的权限码是
     * {@code ["*"]}，可见全部已启用的表，不受模块限制。</p>
     */
    private Scope resolveScope(List<String> permissionCodes) {
        Set<String> modules = new LinkedHashSet<>();
        boolean admin = false;
        for (String code : permissionCodes == null ? List.<String>of() : permissionCodes) {
            if (code.isBlank()) {
                continue;
            }
            String trimmed = code.trim();
            if (ADMIN_CODE.equals(trimmed)) {
                admin = true;
                break;
            }
            int colon = trimmed.indexOf(':');
            // 取冒号前的模块前缀
            modules.add(colon > 0 ? trimmed.substring(0, colon) : trimmed);
        }
        return new Scope(admin, modules);
    }

    /**
     * 可见范围。
     *
     * @param admin   是否超管
     * @param modules 可见模块
     */
    private record Scope(boolean admin, Set<String> modules) {
    }
}