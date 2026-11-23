package com.qiheng.erp.ai.action;

/**
 * 不需要业务参数的采购单动作：提交审核或取消。
 *
 * @author Li
 * @since 2026-11-23
 */
public enum PurchaseOrderActionType {

    /** 提交审核。 */
    SUBMIT,

    /** 取消采购单。 */
    CANCEL
}