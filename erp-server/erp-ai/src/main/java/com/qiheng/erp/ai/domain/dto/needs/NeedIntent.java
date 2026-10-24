package com.qiheng.erp.ai.domain.dto.needs;

/**
 * 需求意图：这一条需求要的是事实，还是建议。
 *
 * <p>意图是需求性质而不是执行细节，所以由 planner 在拆需求时一次定死。
 * {@code DATA} 走主循环取数；{@code ADVICE} 走方案链路，产出多张候选方案卡，
 * 由用户多选后再回 planner 拆一轮。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
public enum NeedIntent {

    /** 要事实：查到什么就是什么，完成判据是证据齐备。 */
    DATA,

    /** 要建议：产出候选方案供用户选择，方案本身要经过可行性验证。 */
    ADVICE
}
