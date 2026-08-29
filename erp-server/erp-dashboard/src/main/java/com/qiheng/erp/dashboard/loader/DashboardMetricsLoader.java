package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.dashboard.cache.DashboardPrevValueCache;
import com.qiheng.erp.dashboard.domain.vo.DashboardMetricVO;
import com.qiheng.erp.dashboard.permission.DashboardPermissionGuard;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.enums.SalesOrderStatus;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBill;
import com.qiheng.erp.warehouse.domain.stockbill.enums.StockBillStatus;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import com.qiheng.erp.warehouse.mapper.OutboundBillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作台首屏经营指标聚合器。
 *
 * <p>返回 4 个核心指标，按业务语义分两类口径：</p>
 * <ul>
 *   <li><b>财务类（月累计）</b>：本月销售额 / 本月毛利额；
 *       对比期 = 上月整月累计（Redis 月快照，miss 时实时聚合兜底）</li>
 *   <li><b>运营类（状态快照）</b>：待处理订单数 / 库存风险 SKU 数；
 *       对比期 = 昨日 23:55 快照（Redis 日快照，miss 时变化率返回 0）</li>
 * </ul>
 *
 * <p>子项按当前用户业务权限裁剪：
 * <ul>
 *   <li>本月销售额 ↔ sales:query</li>
 *   <li>本月毛利额 ↔ sales:query + purchase:query</li>
 *   <li>待处理订单：采购子项 ↔ purchase:query、销售子项 ↔ sales:query、
 *       入库子项 ↔ warehouse:query、出库子项 ↔ warehouse:query</li>
 *   <li>库存风险 SKU 数 ↔ warehouse:query</li>
 * </ul>
 *
 * <p>无权子项返回 {@code value=0}，卡片整体仍展示，提示由前端按中性样式渲染。
 * {@code changeRate} 与 {@code status} 由 {@link DashboardPrevValueCache} 自动计算。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DashboardMetricsLoader {
    private final DashboardPermissionGuard permissionGuard;
    private final SalesOrderMapper salesOrderMapper;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final InboundBillMapper inboundBillMapper;
    private final OutboundBillMapper outboundBillMapper;
    private final DashboardStockAlertLoader stockAlertLoader;
    private final DashboardPrevValueCache prevValueCache;

    /**
     * 加载首屏经营指标
     * @param user 当前登录用户
     * @return 4 个核心指标的 VO 列表，顺序固定；无权子项 value=0
     */
    public List<DashboardMetricVO> load(LoginUser user) {
        // 1. 处理时间范围
        LocalDate today = LocalDate.now();
        // 月初第一天
        LocalDate monthStart = today.withDayOfMonth(1);
        // 本月开始时间
        LocalDateTime monthStartTime = monthStart.atStartOfDay();
        // 当前时刻
        LocalDateTime now = LocalDateTime.now();

        // 2. 检查用户是否有权限查看
        boolean canSales = permissionGuard.canViewSales(user);
        boolean canPurchase = permissionGuard.canViewPurchase(user);
        boolean canWarehouse = permissionGuard.canViewWarehouse(user);

        // 3. 财务类指标：本月累计 + 上月整月对比
        BigDecimal monthSales = canSales
                ? sumSalesApprovedBetween(monthStartTime, now)
                : BigDecimal.ZERO;
        BigDecimal monthPurchase = canPurchase
                ? sumPurchaseApprovedBetween(monthStartTime, now)
                : BigDecimal.ZERO;
        BigDecimal monthGross = (canSales && canPurchase)
                ? monthSales.subtract(monthPurchase)
                : BigDecimal.ZERO;

        // 上月整月累计：优先读月快照，miss 时实时聚合兜底
        YearMonth prevMonth = YearMonth.from(monthStart).minusMonths(1);
        BigDecimal prevMonthSales = resolveMonthlySnapshot(prevMonth,
                DashboardPrevValueCache.MonthlySnapshotType.SALES_TOTAL,
                canSales ? this::sumPrevMonthSales : null);
        BigDecimal prevMonthPurchase = resolveMonthlySnapshot(prevMonth,
                DashboardPrevValueCache.MonthlySnapshotType.PURCHASE_TOTAL,
                canPurchase ? this::sumPrevMonthPurchase : null);
        // 上月毛利只在两个上月累计都有值且当前用户有毛利权限时才可比，否则视为 0（不误导）
        BigDecimal prevMonthGross = (canSales && canPurchase
                && prevMonthSales != null && prevMonthPurchase != null)
                ? prevMonthSales.subtract(prevMonthPurchase)
                : BigDecimal.ZERO;

        // 4. 运营类指标：当前快照 + 昨日 23:55 快照对比
        long pendingPurchase = canPurchase ? purchaseOrderMapper.selectCount(
                new LambdaQueryWrapper<PurchaseOrder>()
                        .eq(PurchaseOrder::getStatus, PurchaseOrderStatus.SUBMITTED.name())
                        .eq(PurchaseOrder::getDeleted, 0))
                : 0L;
        long pendingSales = canSales ? salesOrderMapper.selectCount(
                new LambdaQueryWrapper<SalesOrder>()
                        .eq(SalesOrder::getStatus, SalesOrderStatus.SUBMITTED.name())
                        .eq(SalesOrder::getDeleted, 0))
                : 0L;
        long pendingInbound = canWarehouse ? inboundBillMapper.selectCount(
                new LambdaQueryWrapper<InboundBill>()
                        .eq(InboundBill::getStatus, StockBillStatus.PENDING_CONFIRM.name()))
                : 0L;
        long pendingOutbound = canWarehouse ? outboundBillMapper.selectCount(
                new LambdaQueryWrapper<OutboundBill>()
                        .eq(OutboundBill::getStatus, StockBillStatus.PENDING_CONFIRM.name()))
                : 0L;
        long pendingOrder = pendingPurchase + pendingSales + pendingInbound + pendingOutbound;
        long stockRisk = canWarehouse ? stockAlertLoader.countRiskSkus() : 0L;

        // 昨日快照：miss 时变化率按 0 处理（跨天 23:55~24:00 调度间隙）
        LocalDate yesterday = today.minusDays(1);
        BigDecimal prevPending = canSales || canPurchase || canWarehouse
                ? prevValueCache.getDailySnapshot(yesterday, DashboardPrevValueCache.DailySnapshotType.PENDING_COUNT)
                : BigDecimal.ZERO;
        BigDecimal prevStockRisk = canWarehouse
                ? prevValueCache.getDailySnapshot(yesterday, DashboardPrevValueCache.DailySnapshotType.STOCK_RISK_COUNT)
                : BigDecimal.ZERO;

        // 5. 构建指标 VO 列表，changeRate / status 自动计算
        List<DashboardMetricVO> metrics = new ArrayList<>();
        metrics.add(buildMetric(canSales ? "本月销售额" : "本月销售额（无销售权限）",
                QtyUtil.toDecimal(monthSales), "元",
                DashboardPrevValueCache.computeChangeRate(monthSales, prevMonthSales),
                "较上月",
                DashboardPrevValueCache.computeStatus(
                        DashboardPrevValueCache.computeChangeRate(monthSales, prevMonthSales))));
        metrics.add(buildMetric((canSales && canPurchase) ? "本月毛利额" : "本月毛利额（无毛利权限）",
                QtyUtil.toDecimal(monthGross), "元",
                DashboardPrevValueCache.computeChangeRate(monthGross, prevMonthGross),
                "较上月",
                DashboardPrevValueCache.computeStatus(
                        DashboardPrevValueCache.computeChangeRate(monthGross, prevMonthGross))));
        metrics.add(buildMetric("待处理订单", BigDecimal.valueOf(pendingOrder), "单",
                DashboardPrevValueCache.computeChangeRate(BigDecimal.valueOf(pendingOrder), prevPending),
                "较昨日",
                DashboardPrevValueCache.computeStatus(
                        DashboardPrevValueCache.computeChangeRate(BigDecimal.valueOf(pendingOrder), prevPending))));
        metrics.add(buildMetric(canWarehouse ? "库存风险 SKU" : "库存风险 SKU（无库存权限）",
                BigDecimal.valueOf(stockRisk), "个",
                DashboardPrevValueCache.computeChangeRate(BigDecimal.valueOf(stockRisk), prevStockRisk),
                "较昨日",
                DashboardPrevValueCache.computeStatus(
                        DashboardPrevValueCache.computeChangeRate(BigDecimal.valueOf(stockRisk), prevStockRisk))));
        return metrics;
    }

    /**
     * 解析月快照值：优先 Redis 缓存；miss 时用 supplier 实时聚合并回填缓存。
     *
     * @param prevMonth 上月
     * @param type 快照类型
     * @param fallback 实时聚合函数；null 表示当前用户无对应权限，直接返回 0
     * @return 上月累计值；无权限时返回 0，缓存与聚合均失败时返回 null（变化率按 0 处理）
     */
    private BigDecimal resolveMonthlySnapshot(YearMonth prevMonth,
                                              DashboardPrevValueCache.MonthlySnapshotType type,
                                              java.util.function.Supplier<BigDecimal> fallback) {
        if (fallback == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal cached = prevValueCache.getMonthlySnapshot(prevMonth, type);
        if (cached != null) {
            return cached;
        }
        BigDecimal computed;
        try {
            computed = fallback.get();
        } catch (Exception ex) {
            log.warn("工作台月快照实时聚合失败, month={}, type={}", prevMonth, type, ex);
            return null;
        }
        try {
            prevValueCache.putMonthlySnapshot(prevMonth, type, computed);
        } catch (Exception ex) {
            log.warn("工作台月快照缓存回填失败, month={}, type={}", prevMonth, type, ex);
        }
        return computed;
    }

    /** 上月整月销售累计（实时聚合，仅 cache miss 时调用） */
    private BigDecimal sumPrevMonthSales() {
        YearMonth prevMonth = YearMonth.now().minusMonths(1);
        return sumSalesApprovedBetween(
                prevMonth.atDay(1).atStartOfDay(),
                prevMonth.atEndOfMonth().atTime(LocalTime.MAX));
    }

    /** 上月整月采购累计（实时聚合，仅 cache miss 时调用） */
    private BigDecimal sumPrevMonthPurchase() {
        YearMonth prevMonth = YearMonth.now().minusMonths(1);
        return sumPurchaseApprovedBetween(
                prevMonth.atDay(1).atStartOfDay(),
                prevMonth.atEndOfMonth().atTime(LocalTime.MAX));
    }

    /** 销售单过审后总金额（按 approved_at 在时间段内聚合） */
    private BigDecimal sumSalesApprovedBetween(LocalDateTime start, LocalDateTime end) {
        List<SalesOrder> orders = salesOrderMapper.selectList(
                new LambdaQueryWrapper<SalesOrder>()
                        .eq(SalesOrder::getStatus, SalesOrderStatus.OUTBOUND_DONE.name())
                        .between(SalesOrder::getApprovedAt, start, end)
                        .eq(SalesOrder::getDeleted, 0));
        BigDecimal total = BigDecimal.ZERO;
        for (SalesOrder order : orders) {
            if (order.getTotalAmount() != null) {
                total = total.add(BigDecimal.valueOf(order.getTotalAmount()));
            }
        }
        return total;
    }

    /** 采购单过审后总金额（按 approved_at 在时间段内聚合） */
    private BigDecimal sumPurchaseApprovedBetween(LocalDateTime start, LocalDateTime end) {
        List<PurchaseOrder> orders = purchaseOrderMapper.selectList(
                new LambdaQueryWrapper<PurchaseOrder>()
                        .eq(PurchaseOrder::getStatus, PurchaseOrderStatus.INBOUND_DONE.name())
                        .between(PurchaseOrder::getApprovedAt, start, end)
                        .eq(PurchaseOrder::getDeleted, 0));
        BigDecimal total = BigDecimal.ZERO;
        for (PurchaseOrder order : orders) {
            if (order.getTotalAmount() != null) {
                total = total.add(BigDecimal.valueOf(order.getTotalAmount()));
            }
        }
        return total;
    }

    private static DashboardMetricVO buildMetric(String label, BigDecimal value, String unit,
                                                 BigDecimal changeRate, String compareText, String status) {
        DashboardMetricVO vo = new DashboardMetricVO();
        vo.setLabel(label);
        vo.setValue(value);
        vo.setUnit(unit);
        vo.setChangeRate(changeRate);
        vo.setCompareText(compareText);
        vo.setStatus(status);
        return vo;
    }
}
