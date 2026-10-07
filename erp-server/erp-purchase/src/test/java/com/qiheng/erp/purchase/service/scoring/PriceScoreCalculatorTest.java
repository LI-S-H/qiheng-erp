package com.qiheng.erp.purchase.service.scoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * PriceScoreCalculator 单元测试。
 *
 * <p>覆盖正常路径 + 边界值(null/0/负数/截顶)。</p>
 *
 * @author Li
 * @since 2026-09-24
 */
class PriceScoreCalculatorTest {

    private final PriceScoreCalculator calculator = new PriceScoreCalculator();

    @Test
    void calculateShouldReturnTenThousandWhenQuoteBelowReferencePrice() {
        // 参考价 4000 / 报价 3000 = 1.333 → ×10000 = 13333 → 截顶 10000
        Integer result = calculator.calculate(4000L, 3000L);
        assertEquals(Integer.valueOf(10000), result);
    }

    @Test
    void calculateShouldReturnProportionalWhenQuoteAboveReferencePrice() {
        // 参考价 3000 / 报价 5000 = 0.6 → ×10000 = 6000
        Integer result = calculator.calculate(3000L, 5000L);
        assertEquals(Integer.valueOf(6000), result);
    }

    @Test
    void calculateShouldReturnEqualWhenQuoteEqualsReferencePrice() {
        // 参考价 3500 / 报价 3500 = 1.0 → ×10000 = 10000
        Integer result = calculator.calculate(3500L, 3500L);
        assertEquals(Integer.valueOf(10000), result);
    }

    @Test
    void calculateShouldHandleHalfUpRounding() {
        // 参考价 1000 / 报价 3000 = 0.3333 → ×10000 = 3333.33 → HALF_UP → 3333
        Integer result = calculator.calculate(1000L, 3000L);
        assertEquals(Integer.valueOf(3333), result);
    }

    @Test
    void calculateShouldReturnNullWhenReferencePriceIsNull() {
        assertNull(calculator.calculate(null, 3000L));
    }

    @Test
    void calculateShouldReturnNullWhenReferencePriceIsZero() {
        assertNull(calculator.calculate(0L, 3000L));
    }

    @Test
    void calculateShouldReturnNullWhenReferencePriceIsNegative() {
        assertNull(calculator.calculate(-100L, 3000L));
    }

    @Test
    void calculateShouldReturnNullWhenQuoteIsNull() {
        assertNull(calculator.calculate(3500L, null));
    }

    @Test
    void calculateShouldReturnNullWhenQuoteIsZero() {
        assertNull(calculator.calculate(3500L, 0L));
    }

    @Test
    void calculateShouldReturnNullWhenQuoteIsNegative() {
        assertNull(calculator.calculate(3500L, -100L));
    }
}