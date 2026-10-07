package com.qiheng.erp.purchase.service.scoring;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 交付分计算器(供应商维度)。
 *
 * <p>公式:</p>
 * <pre>
 * delivery_score = max(0, 10000 × (1 - penaltyAmount / dueAmount))
 * </pre>
 *
 * <p>前提:180 天内已到承诺日的有效订单应交金额合计 = dueAmount，包含未完全入库订单。
 * dueAmount = 0 → delivery_score = null(NOT_READY)。</p>
 *
 * <p>penaltyAmount 是已乘逾期系数(按时 0% / 1-3 天 25% / 4-7 天 50% / 8-15 天 75% / >15 天 100%)的受罚总额。
 * 已交付批次按确认日期计罚，未交付剩余金额按当前业务日期计罚。</p>
 *
 * @author Li
 * @since 2026-09-23
 */
@Component
public class DeliveryScoreCalculator {

    private static final BigDecimal SCORE_MAX = new BigDecimal(10000);

    /**
     * 使用整数分罚额计算交付分，保留已有调用签名。
     * 新交付事实应调用 calculateAmounts，避免提前舍入亚分罚额。
     *
     * @param dueAmount 应交金额，单位为分
     * @param penaltyAmount 已折算逾期系数的整数分罚额
     * @return INT×100 交付分；输入缺失或应交金额非正时返回 null
     */
    public Integer calculate(Long dueAmount, Long penaltyAmount) {
        return calculateAmounts(dueAmount, penaltyAmount == null ? null : BigDecimal.valueOf(penaltyAmount));
    }

    /**
     * 使用未舍入罚额计算交付分，仅对最终评分进行四舍五入。
     *
     * @param dueAmount 应交金额，单位为分
     * @param penaltyAmount 已折算逾期系数的罚额，保留亚分精度
     * @return INT×100 交付分；输入缺失或应交金额非正时返回 null
     */
    public Integer calculateAmounts(Long dueAmount, BigDecimal penaltyAmount) {
        //  任一参数为 null → 返回 null（表示"未就绪/NOT_READY"）
        if (dueAmount == null || penaltyAmount == null) {
            return null;
        }
        //  应交金额 ≤ 0 → 无法计算比率，返回 null
        if (dueAmount <= 0) {
            return null;
        }
        // 最终分数仅舍入一次，不能先舍入比率后 intValue 截断。
        BigDecimal due = BigDecimal.valueOf(dueAmount);
        BigDecimal raw = due.subtract(penaltyAmount).multiply(SCORE_MAX)
                .divide(due, 0, RoundingMode.HALF_UP);
        // 限制在 [0, 10000] 区间内
        BigDecimal capped = raw.max(BigDecimal.ZERO).min(SCORE_MAX);
        return capped.intValue();
    }
}
