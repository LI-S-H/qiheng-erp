package com.qiheng.erp.dashboard.domain.enums;

/**
 * 工作台待办详情模型。
 *
 * <p>前端只依据该字段选择固定的业务卡片，不再猜测通用摘要字段的业务含义。</p>
 */
public enum DashboardTodoDetailModel {
    // 采购订单审批
    PURCHASE_ORDER_APPROVAL,
    // 销售订单审批
    SALES_ORDER_APPROVAL,
    // 采购退货审批
    PURCHASE_RETURN_APPROVAL,
    // 销售退货审批
    SALES_RETURN_APPROVAL,
    // 入库确认
    INBOUND_CONFIRM,
    // 出库确认
    OUTBOUND_CONFIRM,
    // 库存风险审核
    STOCK_RISK_REVIEW,
    // 系统异常
    SYSTEM_EXCEPTION
}
