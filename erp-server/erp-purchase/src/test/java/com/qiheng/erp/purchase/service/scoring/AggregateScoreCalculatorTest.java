package com.qiheng.erp.purchase.service.scoring;

import com.qiheng.erp.purchase.domain.supplierscore.dto.SupplierPricePair;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AggregateScoreCalculator 单元测试。
 *
 * <p>权重 30% 价格 + 30% 交付 + 30% 质量 + 10% 服务。
 * 任一指标 null → 综合 null,不重新分配权重。</p>
 *
 * @author Li
 * @since 2026-09-24
 */
class AggregateScoreCalculatorTest {

    private final AggregateScoreCalculator calculator = new AggregateScoreCalculator();

    // ---------- recommend score ----------

    @Test
    void calculateRecommendShouldEqualWeightedSum() {
        // 8000×0.3 + 7000×0.3 + 9000×0.3 + 6000×0.1 = 2400+2100+2700+600 = 7800
        Integer result = calculator.calculateRecommendScore(8000, 7000, 9000, 6000);
        assertEquals(Integer.valueOf(7800), result);
    }

    @Test
    void calculateRecommendShouldReturnNullWhenPriceIsNull() {
        assertNull(calculator.calculateRecommendScore(null, 7000, 9000, 6000));
    }

    @Test
    void calculateRecommendShouldReturnNullWhenDeliveryIsNull() {
        assertNull(calculator.calculateRecommendScore(8000, null, 9000, 6000));
    }

    @Test
    void calculateRecommendShouldReturnNullWhenQualityIsNull() {
        assertNull(calculator.calculateRecommendScore(8000, 7000, null, 6000));
    }

    @Test
    void calculateRecommendShouldReturnNullWhenServiceIsNull() {
        assertNull(calculator.calculateRecommendScore(8000, 7000, 9000, null));
    }

    // ---------- overall score ----------

    @Test
    void calculateOverallShouldEqualWeightedSum() {
        Integer result = calculator.calculateOverallScore(8000, 7000, 9000, 6000);
        assertEquals(Integer.valueOf(7800), result);
    }

    @Test
    void calculateOverallShouldReturnNullWhenAnyNull() {
        assertNull(calculator.calculateOverallScore(null, 7000, 9000, 6000));
        assertNull(calculator.calculateOverallScore(8000, null, 9000, 6000));
        assertNull(calculator.calculateOverallScore(8000, 7000, null, 6000));
        assertNull(calculator.calculateOverallScore(8000, 7000, 9000, null));
    }

    @Test
    void calculateOverallShouldClampToRange() {
        // 都是 10000 → 加权和 = 10000,不超界
        assertEquals(Integer.valueOf(10000),
                calculator.calculateOverallScore(10000, 10000, 10000, 10000));
    }

    @Test
    void roundsWeightedScoreHalfUp() {
        assertEquals(8001, calculator.calculateRecommendScore(8001, 8001, 8000, 8000));
        assertEquals(8001, calculator.calculateOverallScore(8001, 8001, 8000, 8000));
    }

    // ---------- aggregateSupplierPrice ----------

    @Test
    void aggregateSupplierPriceShouldWeightedAverage() {
        // (5000×80 + 3000×60) / (5000+3000) = (400000+180000)/8000 = 580000/8000 = 72.5 → 73
        List<SupplierPricePair> pairs = Arrays.asList(
                new SupplierPricePair(5000L, 80),
                new SupplierPricePair(3000L, 60));
        Integer result = calculator.aggregateSupplierPrice(pairs);
        assertEquals(Integer.valueOf(73), result);
    }

    @Test
    void aggregateSupplierPriceShouldReturnNullForEmptyList() {
        assertNull(calculator.aggregateSupplierPrice(new ArrayList<>()));
        assertNull(calculator.aggregateSupplierPrice(null));
    }

    @Test
    void aggregateSupplierPriceShouldNotOverflowWithLargeHistoricalAmounts() {
        // 两笔金额之和及金额乘分数均超出 long 的安全计算范围。
        List<SupplierPricePair> pairs = List.of(
                new SupplierPricePair(Long.MAX_VALUE - 10, 10000),
                new SupplierPricePair(Long.MAX_VALUE - 20, 0));
        assertEquals(5000, calculator.aggregateSupplierPrice(pairs));
    }

    @Test
    void aggregateSupplierPriceShouldSkipZeroAmount() {
        // amount=0 的会被 record 构造器拦截,这里只测 spAmount>0 的混合
        // 但 SupplierPricePair 不允许 spAmount <= 0,所以这场景不会出现
        // 仅测 1 个非零 pair
        List<SupplierPricePair> pairs = List.of(new SupplierPricePair(1000L, 80));
        Integer result = calculator.aggregateSupplierPrice(pairs);
        assertEquals(Integer.valueOf(80), result);
    }

    // ---------- SupplierPricePair 构造器校验 ----------

    @Test
    void supplierPricePairShouldRejectZeroAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> new SupplierPricePair(0L, 80));
    }

    @Test
    void supplierPricePairShouldRejectNegativeAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> new SupplierPricePair(-100L, 80));
    }

    @Test
    void supplierPricePairShouldRejectPriceBelowZero() {
        assertThrows(IllegalArgumentException.class,
                () -> new SupplierPricePair(1000L, -1));
    }

    @Test
    void supplierPricePairShouldRejectPriceAboveTenThousand() {
        assertThrows(IllegalArgumentException.class,
                () -> new SupplierPricePair(1000L, 10001));
    }
}
