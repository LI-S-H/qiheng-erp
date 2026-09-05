package com.qiheng.erp.dashboard.cache;

import com.qiheng.erp.dashboard.domain.enums.MetricDirection;
import com.qiheng.erp.dashboard.domain.enums.MetricStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PrevValueCacheTest {

    @Test
    void shouldUseAbsolutePreviousValueForLossImprovement() {
        BigDecimal rate = PrevValueCache.computeChangeRate(new BigDecimal("-50"), new BigDecimal("-100"));

        assertThat(rate).isEqualByComparingTo("50.00");
        assertThat(PrevValueCache.computeStatus(rate, MetricDirection.POSITIVE)).isEqualTo(MetricStatus.GOOD);
    }

    @Test
    void shouldKeepCrossingZeroDirection() {
        assertThat(PrevValueCache.computeChangeRate(new BigDecimal("50"), new BigDecimal("-100")))
                .isEqualByComparingTo("150.00");
        assertThat(PrevValueCache.computeChangeRate(new BigDecimal("-50"), new BigDecimal("100")))
                .isEqualByComparingTo("-150.00");
    }

    @Test
    void shouldHideRateWhenBaselineCannotBeCompared() {
        assertThat(PrevValueCache.computeChangeRate(BigDecimal.TEN, null)).isNull();
        assertThat(PrevValueCache.computeChangeRate(BigDecimal.TEN, BigDecimal.ZERO)).isNull();
        assertThat(PrevValueCache.computeChangeRate(BigDecimal.ZERO, BigDecimal.ZERO))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void shouldUseMetricDirectionForStatus() {
        assertThat(PrevValueCache.computeStatus(new BigDecimal("-20"), MetricDirection.NEGATIVE))
                .isEqualTo(MetricStatus.GOOD);
        assertThat(PrevValueCache.computeStatus(new BigDecimal("3"), MetricDirection.POSITIVE))
                .isEqualTo(MetricStatus.WATCH);
        assertThat(PrevValueCache.computeStatus(new BigDecimal("-3"), MetricDirection.POSITIVE))
                .isEqualTo(MetricStatus.WATCH);
    }
    @Test
    void shouldUseKebabCaseSnapshotKeySegments() {
        assertThat(PrevValueCache.DailySnapshotType.STOCK_RISK_COUNT.keySegment())
                .isEqualTo("stock-risk-count");
        assertThat(PrevValueCache.MonthlySnapshotType.SALES_TOTAL.keySegment())
                .isEqualTo("sales-total");
        assertThat(PrevValueCache.MonthlySnapshotType.PURCHASE_TOTAL.keySegment())
                .isEqualTo("purchase-total");
    }
}
