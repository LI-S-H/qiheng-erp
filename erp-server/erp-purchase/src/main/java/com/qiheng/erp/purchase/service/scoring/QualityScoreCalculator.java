package com.qiheng.erp.purchase.service.scoring;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * 质量分计算器（供货产品及供应商维度）。
 *
 * <p>公式:</p>
 * <pre>
 * quality_score = 合格金额 / (合格金额 + 不合格金额) × 10000
 * </pre>
 *
 * <p>前提:180 天内完全入库订单的确认入库质检金额。
 * 总金额为 0 → null；全不合格 → 0；全合格 → 10000。</p>
 *
 * <p>内部 BigDecimal 计算,最终 HALF_UP 转 INT×100。</p>
 *
 * @author Li
 * @since 2026-09-23
 */
@Component
public class QualityScoreCalculator {

    private static final BigDecimal SCORE_MAX = new BigDecimal(10000);

    /**
     * 使用同一单位的两个累计值计算质量分，保留已有调用签名。
     * 新金额事实应使用 calculateAmounts，避免将大整数缩窄为 Long。
     *
     * @param qualifiedQty 合格累计值，必须与不合格累计值使用相同单位
     * @param unqualifiedQty 不合格累计值
     * @return INT×100 质量分；输入缺失、为负或合计为零时返回 null
     */
    public Integer calculate(Long qualifiedQty, Long unqualifiedQty) {
        if (qualifiedQty == null || unqualifiedQty == null) {
            return null;
        }
        return calculateAmounts(BigInteger.valueOf(qualifiedQty), BigInteger.valueOf(unqualifiedQty));
    }

    /**
     * 按合格原始金额占比计算质量分，共同缩放倍数在比值中抵消。
     *
     * @param qualifiedAmount 合格原始金额，即数量存储值乘以单价分的总额
     * @param defectiveAmount 不合格原始金额，与合格金额使用相同单位
     * @return INT×100 质量分；输入缺失、为负或合计为零时返回 null
     */
    public Integer calculateAmounts(BigInteger qualifiedAmount, BigInteger defectiveAmount) {
        if (qualifiedAmount == null || defectiveAmount == null
                || qualifiedAmount.signum() < 0 || defectiveAmount.signum() < 0) return null;
        BigInteger total = qualifiedAmount.add(defectiveAmount);
        if (total.signum() <= 0) {
            return null;
        }
        // 共同缩放倍数在比值中抵消，不必将金额逐批舍入到分。
        BigDecimal numerator = new BigDecimal(qualifiedAmount);
        BigDecimal denominator = new BigDecimal(total);
        // 计算质量分，保留0位小数，HALF_UP四舍五入
        BigDecimal score = numerator.multiply(SCORE_MAX)
                .divide(denominator, 0, RoundingMode.HALF_UP);
        // 截顶保护，返回数据库 INT×100 分数。
        BigDecimal capped = score.min(SCORE_MAX).max(BigDecimal.ZERO);
        return capped.intValue();
    }
}
