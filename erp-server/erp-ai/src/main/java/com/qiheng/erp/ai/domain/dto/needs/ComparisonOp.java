package com.qiheng.erp.ai.domain.dto.needs;

/**
 * 受限比较器的操作符白名单。
 *
 * <p>不支持函数调用、算术表达式与变量组合：条件判定必须是一次比较，
 * 复杂表达交给模型会把"判定权归代码"这条线让出去。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
public enum ComparisonOp {

    GT,
    GTE,
    LT,
    LTE,
    EQ,
    NEQ
}
