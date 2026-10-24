package com.qiheng.erp.ai.constant;

/**
 * 需求选择节点的路由取值。
 *
 * @author Li
 * @since 2026-10-22
 */
public enum NeedSelectorResult {

    /** 已选出当前需求，进入完成判定。 */
    SELECTED,

    /** 全部需求进入终态，进入报告。 */
    ALL_TERMINAL
}
