package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.dashboard.domain.vo.DashboardTrendPointVO;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.enums.SalesOrderStatus;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台经营趋势聚合器。
 *
 * <p>返回近 30 天的销售金额、采购金额和毛利额（销售 - 采购）按天序列。
 * 前端在工作台卡片内提供 7 天、15 天、30 天切换，只做本地截取，不额外调用接口。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Component
public class DashboardTrendLoader {

    private static final int TREND_DAYS = 30;

    private final SalesOrderMapper salesOrderMapper;
    private final PurchaseOrderMapper purchaseOrderMapper;

    @Autowired
    public DashboardTrendLoader(SalesOrderMapper salesOrderMapper,
                                PurchaseOrderMapper purchaseOrderMapper) {
        this.salesOrderMapper = salesOrderMapper;
        this.purchaseOrderMapper = purchaseOrderMapper;
    }

    /**
     * 加载近 30 天经营趋势
     * @return 按日期升序的 30 个趋势点
     */
    public List<DashboardTrendPointVO> load() {
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(TREND_DAYS - 1L);

        List<SalesOrder> sales = salesOrderMapper.selectList(
                new LambdaQueryWrapper<SalesOrder>()
                        .in(SalesOrder::getStatus,
                                SalesOrderStatus.APPROVED.name(),
                                SalesOrderStatus.PARTIAL_OUTBOUND.name(),
                                SalesOrderStatus.OUTBOUND_DONE.name())
                        .between(SalesOrder::getApprovedAt, startDate.atStartOfDay(), today.atTime(23, 59, 59)));
        List<PurchaseOrder> purchases = purchaseOrderMapper.selectList(
                new LambdaQueryWrapper<PurchaseOrder>()
                        .in(PurchaseOrder::getStatus,
                                PurchaseOrderStatus.APPROVED.name(),
                                PurchaseOrderStatus.PARTIAL_INBOUND.name(),
                                PurchaseOrderStatus.INBOUND_DONE.name())
                        .between(PurchaseOrder::getApprovedAt, startDate.atStartOfDay(), today.atTime(23, 59, 59)));

        Map<LocalDate, BigDecimal> salesByDay = aggregateByDay(sales, SalesOrder::getApprovedAt, SalesOrder::getTotalAmount);
        Map<LocalDate, BigDecimal> purchaseByDay = aggregateByDay(purchases, PurchaseOrder::getApprovedAt, PurchaseOrder::getTotalAmount);

        List<DashboardTrendPointVO> points = new ArrayList<>(TREND_DAYS);
        for (int offset = 0; offset < TREND_DAYS; offset++) {
            LocalDate date = startDate.plusDays(offset);
            BigDecimal salesAmount = toYuan(salesByDay.getOrDefault(date, BigDecimal.ZERO));
            BigDecimal purchaseAmount = toYuan(purchaseByDay.getOrDefault(date, BigDecimal.ZERO));
            BigDecimal grossMargin = salesAmount.subtract(purchaseAmount);

            DashboardTrendPointVO vo = new DashboardTrendPointVO();
            vo.setDate(date);
            vo.setSalesAmount(salesAmount);
            vo.setPurchaseAmount(purchaseAmount);
            vo.setGrossMarginAmount(grossMargin);
            points.add(vo);
        }
        return points;
    }

    /** 通用按天聚合：将 entity 按 approved_at 日期聚合 amount 之和 */
    private static <T> Map<LocalDate, BigDecimal> aggregateByDay(List<T> entities,
                                                                java.util.function.Function<T, java.time.LocalDateTime> timeGetter,
                                                                java.util.function.Function<T, Long> amountGetter) {
        Map<LocalDate, BigDecimal> result = new HashMap<>();
        for (T entity : entities) {
            java.time.LocalDateTime when = timeGetter.apply(entity);
            Long amount = amountGetter.apply(entity);
            if (when == null || amount == null) {
                continue;
            }
            LocalDate day = when.toLocalDate();
            result.merge(day, BigDecimal.valueOf(amount), BigDecimal::add);
        }
        return result;
    }

    /** 数据库存储 ×100 转业务小数（保留 2 位） */
    private static BigDecimal toYuan(BigDecimal stored) {
        return stored.divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }
}