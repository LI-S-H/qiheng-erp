package com.qiheng.erp.purchase.service.scoring;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 价格分计算器(产品维度)。
 *
 * <p>公式:</p>
 * <pre>
 * price_score = min(10000, reference_price / quote × 10000)
 * </pre>
 *
 * <p>前提:referencePrice > 0,quote > 0,supplierProduct.status=1,quote_valid_until >= today。
 * 任一不满足 → price_score = null(NOT_READY)。</p>
 *
 * <p>内部用 BigDecimal 保证精度,最终 HALF_UP 转 INT×100。</p>
 *
 * @author Li
 * @since 2026-09-23
 */
@Component
public class PriceScoreCalculator {

    private static final BigDecimal SCORE_MAX = new BigDecimal(10000);

    /**
     * 计算价格分(INT×100)。
     *
     * @param referencePrice 产品参考采购价(分),null 时返回 null
     * @param quote 供货关系有效报价(分),null 或 <= 0 时返回 null
     * @return 价格分(0~10000),不满足前提返回 null
     */
    public Integer calculate(Long referencePrice, Long quote) {
        if (referencePrice == null || referencePrice <= 0) {
            return null;
        }
        if (quote == null || quote <= 0) {
            return null;
        }
        BigDecimal base = new BigDecimal(referencePrice);
        BigDecimal q = new BigDecimal(quote);
        // base / quote × 10000
        BigDecimal raw = base.multiply(SCORE_MAX).divide(q, 0, RoundingMode.HALF_UP);
        // min(10000, raw)
        BigDecimal capped = raw.min(SCORE_MAX);
        return capped.intValue();
    }
}