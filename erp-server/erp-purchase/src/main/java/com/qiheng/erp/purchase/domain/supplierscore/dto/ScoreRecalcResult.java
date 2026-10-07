package com.qiheng.erp.purchase.domain.supplierscore.dto;

import com.qiheng.erp.purchase.domain.supplierscore.enums.MetricType;
import lombok.Data;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * 重算结果。
 *
 * <p>评分入口返回的结果,包含:</p>
 * <ul>
 *   <li>各 metric 维度 before/after 值</li>
 *   <li>是否发生指标或评分状态变化（changed）</li>
 *   <li>metricChanges 同时记录供应商及供货产品的指标、推荐分和综合分变化</li>
 * </ul>
 *
 * @author Li
 * @since 2026-09-23
 */
@Data
@Builder
public class ScoreRecalcResult {

    /** 供应商 ID */
    private Long supplierId;

    /** 指标或评分状态是否变化；只有实际指标变化明细才写变化日志。 */
    private boolean changed;

    /** 各指标变化(用于写日志) */
    private List<MetricChange> metricChanges;

    /**
     * 单个指标的变化记录。
     */
    @Data
    @Builder
    public static class MetricChange {
        /** 指标类型 */
        private MetricType metricType;
        /** 供货关系 ID,供应商级指标为 null */
        private Long supplierProductId;
        /** 指标变化前值，单独推荐分变化时可为空。 */
        private Integer metricScoreBefore;
        /** 指标变化后值，无有效数据时可为空。 */
        private Integer metricScoreAfter;
        /** 产品推荐分变化前值，供应商级记录为空。 */
        private Integer productRecommendScoreBefore;
        /** 产品推荐分变化后值，供应商级记录为空。 */
        private Integer productRecommendScoreAfter;
        /** 供应商综合分变化前值，产品级记录为空。 */
        private Integer supplierOverallScoreBefore;
        /** 供应商综合分变化后值，产品级记录为空。 */
        private Integer supplierOverallScoreAfter;
    }
}
