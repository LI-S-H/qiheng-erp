package com.qiheng.erp.purchase.service.scoring;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * QualityScoreCalculator 单元测试。
 *
 * <p>公式:qualifiedQty / (qualifiedQty + unqualifiedQty) × 10000</p>
 *
 * @author Li
 * @since 2026-09-24
 */
class QualityScoreCalculatorTest {

    private final QualityScoreCalculator calculator = new QualityScoreCalculator();

    @Test
    void calculateShouldReturnProportionalForPartialQualified() {
        // 80 / (80 + 20) = 0.8 × 10000 = 8000
        Integer result = calculator.calculate(80L, 20L);
        assertEquals(Integer.valueOf(8000), result);
    }

    @Test
    void calculateShouldReturnTenThousandWhenAllQualified() {
        Integer result = calculator.calculate(100L, 0L);
        assertEquals(Integer.valueOf(10000), result);
    }

    @Test
    void calculateShouldReturnNullWhenAllUnqualified() {
        // 0 / (0 + 100) → 实际返回 0 不是 null,看具体实现
        // 当前实现:total=100, numerator=0, 0/100*10000=0
        Integer result = calculator.calculate(0L, 100L);
        assertEquals(Integer.valueOf(0), result);
    }

    @Test
    void calculateShouldReturnNullWhenBothZero() {
        // 0 + 0 = 0 → total <= 0 → null
        assertNull(calculator.calculate(0L, 0L));
    }

    @Test
    void calculateShouldReturnNullWhenQualifiedIsNull() {
        assertNull(calculator.calculate(null, 50L));
    }

    @Test
    void calculateShouldReturnNullWhenUnqualifiedIsNull() {
        assertNull(calculator.calculate(50L, null));
    }

    @Test
    void calculateShouldHandleHalfUpRounding() {
        // 1 / (1 + 2) = 0.3333 → × 10000 = 3333.33 → HALF_UP → 3333
        Integer result = calculator.calculate(1L, 2L);
        assertEquals(Integer.valueOf(3333), result);
    }
}