package com.qiheng.erp.dashboard.domain.todo.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 工作台待办来源编码。
 *
 * <p>与 {@code dashboard_todo_item.business_type} 和 OpenAPI {@code DashboardTodoItem.businessType} 保持一致，
 * 后端只用于内部聚合分组与权重计算，前端优先展示 {@code businessLabel} 字段。</p>
 */
@Getter
@RequiredArgsConstructor
public enum DashboardTodoType {

    PURCHASE_APPROVE(
            "todo-purchase-approve", "PURCHASE", "采购",
            "采购单待审核", "还有 %d 张采购单需要审核，处理后会自动完成待办。",
            "HIGH", 20, "AUTO",
            "前往采购订单完成审核，审核通过或驳回后该待办自动更新。"),

    SALES_APPROVE(
            "todo-sales-approve", "SALES", "销售",
            "销售单待审核", "还有 %d 张销售单需要审核，处理后会自动完成待办。",
            "HIGH", 21, "AUTO",
            "前往销售订单完成审核，审核通过后进入库存锁定和发货准备。"),

    PURCHASE_RETURN_APPROVE(
            "todo-purchase-return-approve", "PURCHASE", "采购退货",
            "采购退货待审核", "还有 %d 张采购退货单需要审核，处理后会自动完成待办。",
            "HIGH", 22, "AUTO",
            "前往采购退货单完成审核，审核通过后将生成对应出库工作单。"),

    SALES_RETURN_APPROVE(
            "todo-sales-return-approve", "SALES", "销售退货",
            "销售退货待审核", "还有 %d 张销售退货单需要审核，处理后会自动完成待办。",
            "HIGH", 23, "AUTO",
            "前往销售退货单完成审核，审核通过后将生成对应入库工作单。"),

    INBOUND_PENDING(
            "todo-inbound", "WAREHOUSE", "仓储",
            "待确认入库", "还有 %d 张入库单等待仓库确认。",
            "MEDIUM", 50, "AUTO",
            "前往入库单完成确认，确认入库后该待办自动更新。"),

    OUTBOUND_PENDING(
            "todo-outbound", "WAREHOUSE", "仓储",
            "待确认出库", "还有 %d 张出库单等待发货确认。",
            "MEDIUM", 51, "AUTO",
            "前往出库单完成确认，确认出库后该待办自动更新。"),

    STOCK_RISK_REVIEW(
            "todo-stock-risk-review", "INVENTORY", "库存",
            "库存异常待复核", "还有 %d 个 SKU 可用库存低于安全线或已无可用库存，需要复核补货或调拨。",
            "HIGH", 30, "AUTO",
            "前往库存余额查看低库存 SKU，补货计划生成或库存恢复后自动更新。"),

    CREDIT_REVIEW(
            "todo-credit-review", "FINANCE", "财务",
            "客户信用待复核", "还有 %d 个客户信用额度需要复核。",
            "MEDIUM", 40, "AUTO",
            "前往客户信用管理完成复核。"),

    PRICE_REVIEW(
            "todo-price-review", "PURCHASE", "采购",
            "采购价偏离待复核", "还有 %d 条采购价格偏离参考价需要复核。",
            "MEDIUM", 41, "AUTO",
            "前往采购价格复核完成审核。"),

    SYSTEM_EXCEPTION(
            "todo-system-exception", "SYSTEM", "系统",
            "系统异常待处理", "还有 %d 条系统异常需要处理。",
            "HIGH", 10, "TRACKED",
            "前往系统异常查看详情并处理。");

    private final String todoId;
    private final String businessType;
    private final String businessLabel;
    private final String title;
    private final String descriptionTemplate;
    private final String priority;
    private final int sortWeight;
    private final String completionMode;
    private final String resolveHint;
}