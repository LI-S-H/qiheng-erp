package com.qiheng.erp.purchase.domain.supplierscore.enums;

/**
 * 操作人类型。
 *
 * <p>写入 {@code supplier_score_change_log.operator_type} 字段。</p>
 */
public enum OperatorType {
    /** 人工操作，operatorId/operatorName 填实际用户 */
    USER,
    /** 系统任务，operatorName 填场景描述(如 D2-每日兜底) */
    SYSTEM
}