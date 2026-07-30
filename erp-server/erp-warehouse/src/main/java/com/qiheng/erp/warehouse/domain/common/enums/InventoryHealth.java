package com.qiheng.erp.warehouse.domain.common.enums;

/**
 * 库存健康状态。
 * 状态由当前库存、锁定库存和安全库存实时派生，不存入数据库。
 */
public enum InventoryHealth {

    /**
     * 可用库存大于安全库存。
     */
    NORMAL,

    /**
     * 可用库存大于 0，且小于等于安全库存。
     */
    LOW_STOCK,

    /**
     * 当前库存大于 0，但可用库存为 0。
     */
    NO_AVAILABLE,

    /**
     * 当前库存为 0。
     */
    OUT_OF_STOCK
}