package com.qiheng.erp.warehouse.domain.enums;

/**
 * 库存占用状态。
 * 状态由当前库存和锁定库存实时派生，不存入数据库。
 */
public enum ReservationState {

    /**
     * 锁定库存为 0。
     */
    UNLOCKED,

    /**
     * 锁定库存大于 0，且小于当前库存。
     */
    PARTIALLY_LOCKED,

    /**
     * 当前库存大于 0，且已全部锁定。
     */
    FULLY_LOCKED
}