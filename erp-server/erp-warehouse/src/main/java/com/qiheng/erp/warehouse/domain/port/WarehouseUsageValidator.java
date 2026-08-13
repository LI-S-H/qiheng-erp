package com.qiheng.erp.warehouse.domain.port;

import java.util.List;

/**
 * 业务模块向仓储模块提供的仓库引用校验扩展点。
 *
 * <p>仓储模块不依赖采购/销售/退货模块实体，通过该接口收集跨模块的仓库引用校验，
 * 保持依赖方向单向（业务模块 → 仓储）。</p>
 */
public interface WarehouseUsageValidator {

    /**
     * 是否存在未完成的、引用本仓库的业务记录（用于停用校验）。
     * <p>终态已完成或已取消的记录不算"未完成"，允许停用。</p>
     *
     * @param warehouseId 仓库ID
     * @return true=存在未完成引用，false=无
     */
    boolean hasActiveReferences(Long warehouseId);

    /**
     * 是否存在任意状态的、引用本仓库的业务记录（用于删除校验）。
     * <p>任何状态的记录都会破坏追溯链路，删除前必须为空。</p>
     *
     * @param warehouseId 仓库ID
     * @return true=存在任意引用，false=无
     */
    boolean hasAnyReferences(Long warehouseId);

    /**
     * 多个 validator 中任意一个返回 true 即视为 true。
     */
    static boolean anyHasActiveReferences(List<WarehouseUsageValidator> validators, Long warehouseId) {
        if (validators == null || validators.isEmpty()) return false;
        return validators.stream().anyMatch(v -> v.hasActiveReferences(warehouseId));
    }

    /**
     * 多个 validator 中任意一个返回 true 即视为 true。
     */
    static boolean anyHasAnyReferences(List<WarehouseUsageValidator> validators, Long warehouseId) {
        if (validators == null || validators.isEmpty()) return false;
        return validators.stream().anyMatch(v -> v.hasAnyReferences(warehouseId));
    }
}