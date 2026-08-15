package com.qiheng.erp.dashboard.domain.enums;

/**
 * 工作台待办来源编码。
 *
 * <p>与 {@code dashboard_todo_item.business_type} 和 OpenAPI {@code DashboardTodoItem.businessType} 保持一致，
 * 后端只用于内部聚合分组与权重计算，前端优先展示 {@code businessLabel} 字段。</p>
 */
public enum DashboardTodoType {

    /** 采购单待审核 */
    PURCHASE_APPROVE,

    /** 销售单待审核 */
    SALES_APPROVE,

    /** 采购入库待确认 */
    INBOUND_PENDING,

    /** 销售出库待确认 */
    OUTBOUND_PENDING,

    /** 库存风险 SKU 待复核 */
    STOCK_RISK_REVIEW,

    /** 客户信用复核 */
    CREDIT_REVIEW,

    /** 采购价偏离参考价 */
    PRICE_REVIEW,

    /** 系统异常聚合（来源 system_exception 表） */
    SYSTEM_EXCEPTION
}