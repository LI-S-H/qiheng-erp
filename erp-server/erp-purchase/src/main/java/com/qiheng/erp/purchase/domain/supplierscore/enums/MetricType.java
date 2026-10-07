package com.qiheng.erp.purchase.domain.supplierscore.enums;

/**
 * 评分指标类型。
 *
 * <p>写入 {@code supplier_score_change_log.metric_type} 字段。
 * 评分日志查询接口的真实筛选维度。</p>
 *
 * <p>维度区分:</p>
 * <ul>
 *   <li>产品维度(PRICE / QUALITY):{@code supplier_product} 表字段,每个供货关系独立</li>
 *   <li>供应商维度(DELIVERY / SERVICE):{@code supplier} 表字段,一个供应商一份</li>
 * </ul>
 *
 * @author Li
 * @since 2026-09-23
 */
public enum MetricType {
    /** 供货关系价格分(产品维度) */
    PRICE,
    /** 供货关系质量分(产品维度) */
    QUALITY,
    /** 供应商交付分(供应商维度) */
    DELIVERY,
    /** 供应商服务分(供应商维度) */
    SERVICE
}
