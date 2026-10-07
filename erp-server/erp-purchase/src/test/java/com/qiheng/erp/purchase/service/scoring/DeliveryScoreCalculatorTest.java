package com.qiheng.erp.purchase.service.scoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * DeliveryScoreCalculator 单元测试。
 *
 * <p>公式:max(0, 10000 × (1 - penaltyAmount / dueAmount))</p>
 *
 * @author Li
 * @since 2026-09-24
 */
class DeliveryScoreCalculatorTest {

    private final DeliveryScoreCalculator calculator = new DeliveryScoreCalculator();

    @Test
    void calculateShouldReturnTenThousandWhenNoPenalty() {
        Integer result = calculator.calculate(100000L, 0L);
        assertEquals(Integer.valueOf(10000), result);
    }

    @Test
    void calculateShouldReturnZeroWhenFullPenalty() {
        Integer result = calculator.calculate(100000L, 100000L);
        assertEquals(Integer.valueOf(0), result);
    }

    @Test
    void calculateShouldReturnHalfWhenHalfPenalty() {
        // penalty / due = 0.5 → 1 - 0.5 = 0.5 → 5000
        Integer result = calculator.calculate(100000L, 50000L);
        assertEquals(Integer.valueOf(5000), result);
    }

    @Test
    void calculateShouldClampToZeroWhenPenaltyExceedsDue() {
        // penalty > due → 计算结果为负,clamp 到 0
        Integer result = calculator.calculate(50000L, 100000L);
        assertEquals(Integer.valueOf(0), result);
    }

    @Test
    void calculateShouldHandleQuarterPenalty() {
        // 25000 / 100000 = 0.25 → 1 - 0.25 = 0.75 → 7500
        Integer result = calculator.calculate(100000L, 25000L);
        assertEquals(Integer.valueOf(7500), result);
    }

    @Test
    void calculateShouldReturnNullWhenDueAmountIsNull() {
        assertNull(calculator.calculate(null, 50000L));
    }

    @Test
    void calculateShouldReturnNullWhenPenaltyIsNull() {
        assertNull(calculator.calculate(100000L, null));
    }

    @Test
    void calculateShouldReturnNullWhenDueAmountIsZero() {
        assertNull(calculator.calculate(0L, 0L));
    }

    @Test
    void roundsFinalScoreHalfUpInsteadOfTruncating() {
        assertEquals(6667, calculator.calculate(3L, 1L));
    }
}
