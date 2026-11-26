package com.qiheng.erp.ai.order;

import java.util.Set;

/**
 * 单据类工具的名称映射：动作与单据类型只在这里维护，销售、采购、退货共用。
 *
 * @author Li
 * @since 2026-11-25
 */
public final class OrderToolNames {

    private static final Set<String> SALES_TOOLS = Set.of(
            "prepare_sales_order_draft", "update_sales_order_draft",
            "submit_sales_order", "cancel_sales_order");

    private static final Set<String> PURCHASE_TOOLS = Set.of(
            "prepare_purchase_order_draft", "update_purchase_order_draft",
            "submit_purchase_order", "cancel_purchase_order");

    private static final Set<String> RETURN_TOOLS = Set.of(
            "prepare_return_order_draft", "update_return_order_draft",
            "submit_return_order", "cancel_return_order");

    private OrderToolNames() {
    }

    /**
     * 判断是否为需要人工确认的单据工具。
     *
     * @param name 工具名称
     * @return 是单据工具返回 true
     */
    public static boolean isOrderTool(String name) {
        return SALES_TOOLS.contains(name) || PURCHASE_TOOLS.contains(name) || RETURN_TOOLS.contains(name);
    }

    /**
     * 工具名对应的动作：创建、修改、提交、取消。
     *
     * @param name 工具名称
     * @return 动作编码；未知工具原样返回
     */
    public static String actionOf(String name) {
        if (name == null) {
            return "";
        }
        if (name.startsWith("prepare_")) {
            return "CREATE";
        }
        if (name.startsWith("update_")) {
            return "UPDATE";
        }
        if (name.startsWith("submit_")) {
            return "SUBMIT";
        }
        if (name.startsWith("cancel_")) {
            return "CANCEL";
        }
        return name;
    }

    /**
     * 工具名对应的单据类型；退货方向由参数决定，名称无法区分。
     *
     * @param name 工具名称
     * @return SALES / PURCHASE / RETURN；无法判断时返回 null
     */
    public static String orderTypeOf(String name) {
        if (SALES_TOOLS.contains(name)) {
            return "SALES";
        }
        if (PURCHASE_TOOLS.contains(name)) {
            return "PURCHASE";
        }
        if (RETURN_TOOLS.contains(name)) {
            return "RETURN";
        }
        return null;
    }
}