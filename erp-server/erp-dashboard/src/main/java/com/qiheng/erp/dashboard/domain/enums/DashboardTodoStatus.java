package com.qiheng.erp.dashboard.domain.enums;

/**
 * 工作台待办状态。
 *
 * <p>与 OpenAPI {@code DashboardTodoItem.status} 字段以及数据库异常记录的
 * 状态字段保持一致，工作台目前只下发 {@link #PENDING}。</p>
 *
 * @author Li
 * @since 2026-08-29
 */
public enum DashboardTodoStatus {

    /** 待处理 */
    PENDING,

    /** 已完成 */
    DONE,

    /** 已忽略 */
    IGNORED,

    /** 处理中（用于 TRACKED 模式异常已分派但未结案的中间态） */
    PROCESSING;

    /**
     * 获取 OpenAPI / 数据库使用的字符串编码
     * @return 与 schema 一致的大小写编码
     */
    public String getCode() {
        return name();
    }
}
