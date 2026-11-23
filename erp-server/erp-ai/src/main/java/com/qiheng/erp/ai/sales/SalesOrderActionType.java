package com.qiheng.erp.ai.sales;

/**
 * 不需要业务参数的销售单动作：提交审核或取消。
 *
 * @author Li
 * @since 2026-11-23
 */
public enum SalesOrderActionType {

    /** 提交审核。 */
    SUBMIT,

    /** 取消销售单。 */
    CANCEL
}