package com.qiheng.erp.ai.order;

import java.util.Map;

/**
 * 单据确认策略：只负责把销售/采购的原始回执映射成统一确认结构。
 *
 * @author Li
 * @since 2026-11-25
 */
public interface OrderConfirmationStrategy {

    /**
     * 判断当前工具是否属于本策略。
     *
     * @param toolName 工具名称
     * @return 属于返回 true
     */
    boolean supports(String toolName);

    /**
     * 单据类型编码。
     *
     * @return SALES / PURCHASE
     */
    String orderType();

    /**
     * 把草稿回执映射成统一结构。
     *
     * @param receipt 具体回执
     * @param action CREATE / UPDATE
     * @param draft 原始草稿参数
     * @return 统一草稿确认
     */
    OrderDraftConfirmation draftConfirmation(Object receipt, String action, Map<String, Object> draft);

    /**
     * 把提交或取消回执映射成统一结构。
     *
     * @param receipt 具体回执
     * @param action SUBMIT / CANCEL
     * @return 统一动作确认
     */
    OrderActionConfirmation actionConfirmation(Object receipt, String action);
}