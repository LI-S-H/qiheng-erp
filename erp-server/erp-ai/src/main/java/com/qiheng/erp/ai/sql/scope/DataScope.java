package com.qiheng.erp.ai.sql.scope;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据可见范围：权限码 → 模块集合。
 *
 * <p>授权 Schema 与业务口径共用这一份推导——"能查什么"与"该知道什么"必须同源，
 * 否则会出现查得到表却没有对应口径、或者给了口径却查不到表的错配。</p>
 *
 * <p><b>动作口径</b>：只有 {@code <模块>:query} 与 {@code <模块>:manage} 才带数据可见性。
 * ERP 列表页本身要的就是 query 权限（{@code StpUtil.checkPermission("purchase:query")}），
 * 只有 create 权限的用户看不到该模块的列表页；让 AI 替他查，就是绕过页面权限。
 * 动作段不是 query/manage 的权限码（create、execute 等）只算功能权限，不给数据。</p>
 *
 * <p>超管的权限码是 {@code ["*"]}，不受模块限制。</p>
 *
 * @param admin  是否超管
 * @param modules 可见模块集合，保持权限码出现的顺序
 * @author Li
 * @since 2026-10-25
 */
public record DataScope(boolean admin, Set<String> modules) {

    /** 超管权限码，可见全部业务模块。 */
    public static final String ADMIN_CODE = "*";

    /** 带来数据可见性的动作。其余动作只是功能权限，不给数据。 */
    private static final Set<String> DATA_VISIBLE_ACTIONS = Set.of("query", "manage");

    public DataScope {
        modules = Collections.unmodifiableSet(new LinkedHashSet<>(modules));
    }

    /**
     * 从权限码算出可见范围。
     *
     * @param permissionCodes 当前用户的权限码；超管是 {@code ["*"]}
     * @return 可见范围
     */
    public static DataScope of(List<String> permissionCodes) {
        Set<String> modules = new LinkedHashSet<>();
        boolean admin = false;
        for (String code : permissionCodes == null ? List.<String>of() : permissionCodes) {
            if (code == null || code.isBlank()) {
                continue;
            }
            String trimmed = code.trim();
            if (ADMIN_CODE.equals(trimmed)) {
                admin = true;
                break;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                // 没有动作段：只说明能进某个功能，不代表能看这个模块的数据
                continue;
            }
            String module = trimmed.substring(0, colon);
            String action = trimmed.substring(colon + 1);
            if (!DATA_VISIBLE_ACTIONS.contains(action)) {
                continue;
            }
            modules.add(module);
        }
        return new DataScope(admin, modules);
    }

    /**
     * 可见范围是否为空：既不是超管，也没有任何可见模块。
     *
     * @return 为空时返回 true
     */
    public boolean isEmpty() {
        return !admin && modules.isEmpty();
    }
}
