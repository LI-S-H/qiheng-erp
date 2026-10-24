package com.qiheng.erp.ai.constant;

/**
 * planner 的运行模式。
 *
 * <p>模式由应用服务在进入 planner 前根据 State 决定，<b>不交给模型判断</b>——
 * 模型判错会拆出重复需求或漏掉依赖。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
public enum PlannerMode {

    /** 首轮拆解：把用户原话拆成需求数组。 */
    INITIAL,

    /** 反馈补充：根据审阅反馈重写已有需求的证据清单。 */
    REFINE
}
