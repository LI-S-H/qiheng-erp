package com.qiheng.erp.ai.sales;

/**
 * 带参数的销售单动作：创建新草稿或修改已有单据。
 *
 * @author Li
 * @since 2026-11-23
 */
public enum SalesOrderDraftAction {

    /** 生成新的销售草稿。 */
    CREATE,

    /** 修改已经存在的销售单。 */
    UPDATE
}