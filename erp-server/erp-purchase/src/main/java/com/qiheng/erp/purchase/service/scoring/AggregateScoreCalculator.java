package com.qiheng.erp.purchase.service.scoring;

import com.qiheng.erp.purchase.domain.supplierscore.dto.SupplierPricePair;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;

/**
 * 推荐分 / 综合分计算器。
 *
 * <p>权重固定 30% 价格 + 30% 交付 + 30% 质量 + 10% 服务。
 * 任一指标为 null,综合结果也为 null(NOT_READY),不重新分配权重。</p>
 *
 * <p>内部 BigDecimal 计算,最终 HALF_UP 转 INT×100。</p>
 *
 * @author Li
 * @since 2026-09-23
 */
@Component
public class AggregateScoreCalculator {

    /** 价格权重 */
    private static final BigDecimal W_PRICE = new BigDecimal("0.30");
    /** 交付权重 */
    private static final BigDecimal W_DELIVERY = new BigDecimal("0.30");
    /** 质量权重 */
    private static final BigDecimal W_QUALITY = new BigDecimal("0.30");
    /** 服务权重 */
    private static final BigDecimal WS_SERVICE = new BigDecimal("0.10");

    /**
     * 计算供货关系推荐分。
     *
     * @param priceScore 价格分(产品维度),可为 null
     * @param deliveryScore 供应商交付分，使用 INT×100，可为 null
     * @param qualityScore 产品质量分(产品维度),可为 null
     * @param serviceScore 供应商服务分，使用 INT×100，可为 null
     * @return 推荐分;任一指标 null 返回 null
     */
    public Integer calculateRecommendScore(Integer priceScore, Integer deliveryScore,
                                          Integer qualityScore, Integer serviceScore) {
        if (priceScore == null || deliveryScore == null
                || qualityScore == null || serviceScore == null) {
            return null;
        }
        return weighted(priceScore, deliveryScore, qualityScore, serviceScore);
    }

    /**
     * 计算供应商综合分。
     *
     * <p>参数语义与 recommend 一致(交付分/服务分是供应商级,价格/质量汇总也是供应商级)。</p>
     *
     * @param supplierPriceScore 供应商价格分，使用 INT×100，可为 null
     * @param deliveryScore 供应商交付分，使用 INT×100，可为 null
     * @param supplierQualityScore 供应商质量分，使用 INT×100，可为 null
     * @param serviceScore 供应商服务分，使用 INT×100，可为 null
     * @return INT×100 综合分；任一指标缺失时返回 null
     */
    public Integer calculateOverallScore(Integer supplierPriceScore, Integer deliveryScore,
                                         Integer supplierQualityScore, Integer serviceScore) {
        if (supplierPriceScore == null || deliveryScore == null
                || supplierQualityScore == null || serviceScore == null) {
            return null;
        }
        return weighted(supplierPriceScore, deliveryScore, supplierQualityScore, serviceScore);
    }

    /**
     * 供应商级价格汇总:按 score_basis_amount 加权汇总各 SP 的价格分。
     *
     * <p>sum(score_basis_amount_i × price_score_i) / sum(score_basis_amount_i)。</p>
     *
     * <p>{@link SupplierPricePair} 构造器已保证 spAmount > 0 且 priceScore ∈ [0, 10000],
     * 方法内部不再做防御性校验。</p>
     *
     * @param pairs (spAmount, priceScore) 列表,空列表返回 null
     * @return 加权平均后的供应商级价格分(0~10000);无有效输入返回 null
     */
    public Integer aggregateSupplierPrice(List<SupplierPricePair> pairs) {
        if (pairs == null || pairs.isEmpty()) {
            return null;
        }
        BigInteger numerator = BigInteger.ZERO;
        BigInteger denominator = BigInteger.ZERO;
        for (SupplierPricePair pair : pairs) {
            // 历史累计金额可能很大，乘以百分制分值时不能使用 long 中间结果。
            BigInteger amount = BigInteger.valueOf(pair.spAmount());
            numerator = numerator.add(amount.multiply(BigInteger.valueOf(pair.priceScore())));
            denominator = denominator.add(amount);
        }
        // record 构造器保证 spAmount > 0,因此 denominator 必 > 0,无需二次校验
        return new BigDecimal(numerator)
                .divide(new BigDecimal(denominator), 0, RoundingMode.HALF_UP)
                .intValue();
    }

    /** 加权汇总 */
    private Integer weighted(int price, int delivery, int quality, int service) {
        BigDecimal p = new BigDecimal(price).multiply(W_PRICE);
        BigDecimal d = new BigDecimal(delivery).multiply(W_DELIVERY);
        BigDecimal q = new BigDecimal(quality).multiply(W_QUALITY);
        BigDecimal s = new BigDecimal(service).multiply(WS_SERVICE);
        BigDecimal total = p.add(d).add(q).add(s);
        return Math.min(10000, Math.max(0, total.setScale(0, RoundingMode.HALF_UP).intValue()));
    }
}
