package com.qiheng.erp.dashboard.cache;

import com.qiheng.erp.dashboard.domain.enums.MetricDirection;
import com.qiheng.erp.dashboard.domain.enums.MetricStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardPrevValueCacheTest {

    @Test
    void shouldUseAbsolutePreviousValueForLossImprovement() {
        BigDecimal rate = DashboardPrevValueCache.computeChangeRate(new BigDecimal("-50"), new BigDecimal("-100"));

        assertThat(rate).isEqualByComparingTo("50.00");
        assertThat(DashboardPrevValueCache.computeStatus(rate, MetricDirection.POSITIVE)).isEqualTo(MetricStatus.GOOD);
    }

    @Test
    void shouldKeepCrossingZeroDirection() {
        assertThat(DashboardPrevValueCache.computeChangeRate(new BigDecimal("50"), new BigDecimal("-100")))
                .isEqualByComparingTo("150.00");
        assertThat(DashboardPrevValueCache.computeChangeRate(new BigDecimal("-50"), new BigDecimal("100")))
                .isEqualByComparingTo("-150.00");
    }

    @Test
    void shouldHideRateWhenBaselineCannotBeCompared() {
        assertThat(DashboardPrevValueCache.computeChangeRate(BigDecimal.TEN, null)).isNull();
        assertThat(DashboardPrevValueCache.computeChangeRate(BigDecimal.TEN, BigDecimal.ZERO)).isNull();
        assertThat(DashboardPrevValueCache.computeChangeRate(BigDecimal.ZERO, BigDecimal.ZERO))
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void shouldUseMetricDirectionForStatus() {
        assertThat(DashboardPrevValueCache.computeStatus(new BigDecimal("-20"), MetricDirection.NEGATIVE))
                .isEqualTo(MetricStatus.GOOD);
        assertThat(DashboardPrevValueCache.computeStatus(new BigDecimal("3"), MetricDirection.POSITIVE))
                .isEqualTo(MetricStatus.WATCH);
        assertThat(DashboardPrevValueCache.computeStatus(new BigDecimal("-3"), MetricDirection.POSITIVE))
                .isEqualTo(MetricStatus.WATCH);
    }
    @Test
    void shouldUseKebabCaseSnapshotKeySegments() {
        assertThat(DashboardPrevValueCache.DailySnapshotType.STOCK_RISK_COUNT.keySegment())
                .isEqualTo("stock-risk-count");
        assertThat(DashboardPrevValueCache.MonthlySnapshotType.SALES_TOTAL.keySegment())
                .isEqualTo("sales-total");
        assertThat(DashboardPrevValueCache.MonthlySnapshotType.PURCHASE_TOTAL.keySegment())
                .isEqualTo("purchase-total");
    }
}
