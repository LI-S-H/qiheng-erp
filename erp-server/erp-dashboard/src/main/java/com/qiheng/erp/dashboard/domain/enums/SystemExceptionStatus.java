package com.qiheng.erp.dashboard.domain.enums;

/**
 * 系统异常状态。
 *
 * <p>对应 {@code system_exception.status} 字段，
 * 表由 AI 模块、MQ 消费器、第三方回调处理、定时任务执行和数据补偿任务统一写入。</p>
 */
public enum SystemExceptionStatus {

    /** 待处理 */
    PENDING,

    /** 处理中 */
    PROCESSING,

    /** 已解决 */
    RESOLVED,

    /** 已忽略 */
    IGNORED
}