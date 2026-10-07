package com.qiheng.erp.purchase.domain.supplierscore.dto;

import com.qiheng.erp.purchase.domain.supplierscore.enums.MetricType;
import lombok.Data;

/**
 * 单条日志条目。
 *
 * <p>对应 {@code supplier_score_change_log} 一行记录。
 * 写入时由 appendBatch() 自动计算 changeKey = SHA-256(batchNo + supplierId + metricType + supplierProductId)。</p>
 *
 * <p>字段可空规则:</p>
 * <ul>
 *   <li>产品日志填写 supplierProductId；服务分、交付分影响产品推荐分时也允许产品日志</li>
 *   <li>供应商日志不填写 supplierProductId 和产品推荐分；产品日志不重复填写供应商综合分</li>
 *   <li>无历史分数或评分失效时对应分数保持 null，不能补成零</li>
 *   <li>仅衍生分变化时基础指标可以为空或前后相同，由公共写入层标明校正说明</li>
 * </ul>
 *
 * @author Li
 * @since 2026-09-23
 */
@Data
public class ScoreChangeLogEntry {
    /** 指标类型 */
    private MetricType metricType;
    /** 供货关系 ID；供应商级日志为 null，产品级日志必填 */
    private Long supplierProductId;
    /** 指标变更前,INT×100 */
    private Integer metricScoreBefore;
    /** 指标变更后,INT×100 */
    private Integer metricScoreAfter;
    /** 产品推荐分变更前；仅产品级日志填写，无分数时为 null */
    private Integer productRecommendScoreBefore;
    /** 产品推荐分变更后 */
    private Integer productRecommendScoreAfter;
    /** 供应商综合分变更前；仅供应商级日志填写 */
    private Integer supplierOverallScoreBefore;
    /** 供应商综合分变更后 */
    private Integer supplierOverallScoreAfter;
}
