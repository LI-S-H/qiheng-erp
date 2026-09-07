package com.qiheng.erp.dashboard.domain.metric.enums;

/**
 * 指标方向枚举，决定变化率与风险色的映射关系。
 *
 * <ul>
 *   <li>{@link #POSITIVE}：正向指标，值越大越好（如销售额、毛利额），上升为良好，下降为风险</li>
 *   <li>{@link #NEGATIVE}：反向指标，值越小越好（如待处理订单、库存风险 SKU），下降为良好，上升为风险</li>
 * </ul>
 */
public enum MetricDirection {

    /** 正向指标：值越大越好 */
    POSITIVE,

    /** 反向指标：值越小越好 */
    NEGATIVE
}