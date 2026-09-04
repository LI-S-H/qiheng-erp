package com.qiheng.erp.dashboard.loader;

import com.qiheng.erp.common.event.dashboard.DashboardTrendMetric;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.dashboard.cache.TrendDailyAmountRefreshService;
import com.qiheng.erp.dashboard.domain.vo.DashboardTrendPointVO;
import com.qiheng.erp.dashboard.permission.DashboardPermissionGuard;
import com.qiheng.erp.security.domain.dto.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 工作台经营趋势聚合器。
 *
 * <p>返回近 30 天销售、采购和毛利（销售 - 采购）按日序列。金额原始分值由按日缓存协调器负责
 * 批量读取及缺失回填；权限仅影响当前用户最终可见的维度。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Component
@RequiredArgsConstructor
public class DashboardTrendLoader {

    private static final int TREND_DAYS = 30;

    private final DashboardPermissionGuard permissionGuard;
    private final TrendDailyAmountRefreshService trendDailyAmountRefreshService;

    /** 加载近 30 天经营趋势。 */
    public List<DashboardTrendPointVO> load(LoginUser user) {
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(TREND_DAYS - 1L);

        boolean canSales = permissionGuard.canViewSales(user);
        boolean canPurchase = permissionGuard.canViewPurchase(user);
        // 1. 从缓存中读取销售、采购、销售退货、采购退货按日序列数据
        Map<LocalDate, Long> salesByDay = canSales
                ? trendDailyAmountRefreshService.resolve(DashboardTrendMetric.SALES, startDate, today)
                : Map.of();
        Map<LocalDate, Long> salesReturnByDay = canSales
                ? trendDailyAmountRefreshService.resolve(DashboardTrendMetric.SALES_RETURN, startDate, today)
                : Map.of();
        Map<LocalDate, Long> purchaseByDay = canPurchase
                ? trendDailyAmountRefreshService.resolve(DashboardTrendMetric.PURCHASE, startDate, today)
                : Map.of();
        Map<LocalDate, Long> purchaseReturnByDay = canPurchase
                ? trendDailyAmountRefreshService.resolve(DashboardTrendMetric.PURCHASE_RETURN, startDate, today)
                : Map.of();

        List<DashboardTrendPointVO> points = new ArrayList<>(TREND_DAYS);
        // 2. 计算销售、采购、毛利按日序列数据
        boolean computeGross = canSales && canPurchase;
        for (int offset = 0; offset < TREND_DAYS; offset++) {
            LocalDate date = startDate.plusDays(offset);
            // 销售净值 = 销售金额 - 销售退货金额
            BigDecimal salesAmount = QtyUtil.toDecimal(salesByDay.getOrDefault(date, 0L)
                    - salesReturnByDay.getOrDefault(date, 0L));
            // 采购净值 = 采购金额 - 采购退货金额
            BigDecimal purchaseAmount = QtyUtil.toDecimal(purchaseByDay.getOrDefault(date, 0L)
                    - purchaseReturnByDay.getOrDefault(date, 0L));
            // 亏损日必须保留负毛利，供前端展示和业务对账使用。
            BigDecimal grossMargin = computeGross ? salesAmount.subtract(purchaseAmount) : BigDecimal.ZERO;
            // 3. 构建趋势点
            DashboardTrendPointVO point = new DashboardTrendPointVO();
            point.setDate(date);
            point.setSalesAmount(salesAmount);
            point.setPurchaseAmount(purchaseAmount);
            point.setGrossMarginAmount(grossMargin);
            points.add(point);
        }
        return points;
    }
}
