package com.qiheng.erp.ai.draft;

/**
 * 带参数的采购单动作：创建新草稿或修改已有单据。
 *
 * @author Li
 * @since 2026-11-23
 */
public enum PurchaseOrderDraftAction {

    /** 生成新的采购草稿。 */
    CREATE,

    /** 修改已经存在的采购单。 */
    UPDATE
}