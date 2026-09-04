package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.dashboard.cache.PrevValueCache;
import com.qiheng.erp.dashboard.cache.model.PendingOrderSnapshot;
import com.qiheng.erp.dashboard.domain.enums.MetricKey;
import com.qiheng.erp.dashboard.domain.enums.MetricStatus;
import com.qiheng.erp.dashboard.domain.enums.OrderMetricScope;
import com.qiheng.erp.dashboard.domain.vo.DashboardMetricVO;
import com.qiheng.erp.dashboard.permission.DashboardPermissionGuard;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.enums.SalesOrderStatus;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.domain.enums.ReturnStatus;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 工作台首屏经营指标聚合器。
 *
 * <p>返回 4 个核心指标，按业务语义分两类口径：</p>
 * <ul>
 *   <li><b>财务类（月累计）</b>：本月销售额 / 本月毛利额；
 *       对比期 = 上月整月累计（Redis 月快照，miss 时实时聚合兜底）</li>
 *   <li><b>运营类（状态快照）</b>：待处理订单数 / 库存风险 SKU 数；
 *       对比期 = 昨日 23:55 快照（Redis 日快照；miss 时明确标记为无可比基线）</li>
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
 * <p>无权子项保留固定卡片位置，{@code value}、{@code changeRate}、{@code compareText} 返回 {@code null}；具体权限状态由概览接口的 {@code access.metrics} 统一表达。
 * {@code changeRate} 与 {@code status} 由 {@link PrevValueCache} 自动计算。</p>
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
    private final ReturnOrderMapper returnOrderMapper;
    private final DashboardStockAlertLoader stockAlertLoader;
    private final PrevValueCache prevValueCache;

    /**
     * 加载首屏经营指标
     * @param user 当前登录用户
     * @return 4 个核心指标的 VO 列表，顺序固定；无权子项的数值与对比字段为 null
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

        // 获取上个月的月份对象
        YearMonth prevMonth = YearMonth.from(monthStart).minusMonths(1);
        // 从 Redis 缓存获取上月销售累计值,若 miss 则实时聚合
        BigDecimal prevMonthSales = resolveMonthlySnapshot(prevMonth,
                PrevValueCache.MonthlySnapshotType.SALES_TOTAL,
                canSales ? () -> sumSalesApprovedBetween(
                        prevMonth.atDay(1).atStartOfDay(), prevMonth.atEndOfMonth().atTime(23, 59, 59)) : null);
        // 从 Redis 缓存获取上月采购累计值,若 miss 则实时聚合
        BigDecimal prevMonthPurchase = resolveMonthlySnapshot(prevMonth,
                PrevValueCache.MonthlySnapshotType.PURCHASE_TOTAL,
                canPurchase ? () -> sumPurchaseApprovedBetween(
                        prevMonth.atDay(1).atStartOfDay(), prevMonth.atEndOfMonth().atTime(23, 59, 59)) : null);
        // 上月毛利只在两个上月累计都有值且当前用户有毛利权限时才可比，否则明确标记为不可比。
        BigDecimal prevMonthGross = (canSales && canPurchase
                && prevMonthSales != null && prevMonthPurchase != null)
                ? prevMonthSales.subtract(prevMonthPurchase)
                : null;

        // 4. 运营类指标：当前快照 + 昨日 23:55 快照对比
        long pendingPurchaseOrder = canPurchase ? purchaseOrderMapper.selectCount(
                new LambdaQueryWrapper<PurchaseOrder>()
                        .eq(PurchaseOrder::getStatus, PurchaseOrderStatus.SUBMITTED.name())
                        .eq(PurchaseOrder::getDeleted, 0))
                : 0L;
        long pendingPurchaseReturn = canPurchase ? countPendingReturns(ReturnType.PURCHASE_RETURN) : 0L;
        long pendingSalesOrder = canSales ? salesOrderMapper.selectCount(
                new LambdaQueryWrapper<SalesOrder>()
                        .eq(SalesOrder::getStatus, SalesOrderStatus.SUBMITTED.name())
                        .eq(SalesOrder::getDeleted, 0))
                : 0L;
        long pendingSalesReturn = canSales ? countPendingReturns(ReturnType.SALES_RETURN) : 0L;
        long pendingInbound = canWarehouse ? inboundBillMapper.selectCount(
                new LambdaQueryWrapper<InboundBill>()
                        .eq(InboundBill::getStatus, StockBillStatus.PENDING_CONFIRM.name()))
                : 0L;
        long pendingOutbound = canWarehouse ? outboundBillMapper.selectCount(
                new LambdaQueryWrapper<OutboundBill>()
                        .eq(OutboundBill::getStatus, StockBillStatus.PENDING_CONFIRM.name()))
                : 0L;
        boolean canViewPending = canSales || canPurchase || canWarehouse;
        PendingOrderSnapshot currentPendingSnapshot = new PendingOrderSnapshot(
                pendingPurchaseOrder, pendingPurchaseReturn, pendingSalesOrder, pendingSalesReturn,
                pendingInbound, pendingOutbound);
        long pendingOrder = sumVisiblePendingOrders(currentPendingSnapshot, canPurchase, canSales, canWarehouse);
        long stockRisk = canWarehouse ? stockAlertLoader.countRiskSkus() : 0L;

        // 昨日待处理订单使用同一权限组合的分项快照；快照缺失时明确标记为不可比较。
        LocalDate yesterday = today.minusDays(1);
        PendingOrderSnapshot prevPendingSnapshot = canViewPending
                ? prevValueCache.getPendingOrderSnapshot(yesterday) : null;
        BigDecimal prevPending = prevPendingSnapshot == null ? null : BigDecimal.valueOf(
                sumVisiblePendingOrders(prevPendingSnapshot, canPurchase, canSales, canWarehouse));
        BigDecimal prevStockRisk = canWarehouse
                ? prevValueCache.getDailySnapshot(yesterday, PrevValueCache.DailySnapshotType.STOCK_RISK_COUNT)
                : BigDecimal.ZERO;

        // 5. 构建固定顺序的指标。无权限时数值和对比字段必须为 null，不能伪装为 0。

        BigDecimal monthSalesChangeRate = canSales
                ? PrevValueCache.computeChangeRate(monthSales, prevMonthSales) : null;
        BigDecimal monthGrossChangeRate = canSales && canPurchase
                ? PrevValueCache.computeChangeRate(monthGross, prevMonthGross) : null;
        BigDecimal pendingOrderChangeRate = canViewPending
                ? PrevValueCache.computeChangeRate(BigDecimal.valueOf(pendingOrder), prevPending) : null;
        BigDecimal stockRiskChangeRate = canWarehouse
                ? PrevValueCache.computeChangeRate(BigDecimal.valueOf(stockRisk), prevStockRisk) : null;

        List<DashboardMetricVO> metrics = new ArrayList<>();
        metrics.add(buildMetric(MetricKey.MONTH_SALES,
                canSales ? QtyUtil.toDecimal(monthSales) : null,
                monthSalesChangeRate,
                canSales ? PrevValueCache.computeStatus(monthSalesChangeRate, MetricKey.MONTH_SALES.getDirection()) : MetricStatus.NEUTRAL));
        metrics.add(buildMetric(MetricKey.MONTH_GROSS_PROFIT,
                canSales && canPurchase ? QtyUtil.toDecimal(monthGross) : null,
                monthGrossChangeRate,
                canSales && canPurchase ? PrevValueCache.computeStatus(monthGrossChangeRate, MetricKey.MONTH_GROSS_PROFIT.getDirection()) : MetricStatus.NEUTRAL));
        metrics.add(buildMetric(MetricKey.PENDING_ORDERS,
                canViewPending ? BigDecimal.valueOf(pendingOrder) : null,
                pendingOrderChangeRate,
                canViewPending ? PrevValueCache.computeStatus(pendingOrderChangeRate, MetricKey.PENDING_ORDERS.getDirection()) : MetricStatus.NEUTRAL));
        metrics.add(buildMetric(MetricKey.STOCK_RISK_SKU,
                canWarehouse ? BigDecimal.valueOf(stockRisk) : null,
                stockRiskChangeRate,
                canWarehouse ? PrevValueCache.computeStatus(stockRiskChangeRate, MetricKey.STOCK_RISK_SKU.getDirection()) : MetricStatus.NEUTRAL));
        return metrics;
    }

    /**
     * 解析月快照值：优先 Redis 缓存；miss 时用 supplier 实时聚合并回填缓存。
     */
    private BigDecimal resolveMonthlySnapshot(YearMonth prevMonth,
                                              PrevValueCache.MonthlySnapshotType type,
                                              Supplier<BigDecimal> fallback) {
        if (fallback == null) {
            return BigDecimal.ZERO;
        }
        // 1. 优先 Redis 缓存
        BigDecimal cached = prevValueCache.getMonthlySnapshot(prevMonth, type);
        if (cached != null) {
            return cached;
        }
        // 2. 缓存未命中，实时聚合并回填缓存
        BigDecimal computed;
        try {
            // 实时获取上月累计值
            computed = fallback.get();
        } catch (Exception ex) {
            log.warn("工作台月快照实时聚合失败, month={}, type={}", prevMonth, type, ex);
            // TODO: 处理异常，例如记录日志、返回默认值等
            return null;
        }
        try {
            // 3. 回填缓存
            prevValueCache.putMonthlySnapshot(prevMonth, type, computed);
        } catch (Exception ex) {
            log.warn("工作台月快照缓存回填失败, month={}, type={}", prevMonth, type, ex);
            // TODO: 处理异常，例如记录日志、返回默认值等
        }
        return computed;
    }

    /** 销售单经营金额（按统一状态、approved_at 和时间段在数据库中聚合）。 */
    private BigDecimal sumSalesApprovedBetween(LocalDateTime start, LocalDateTime end) {
        List<Object> result = salesOrderMapper.selectObjs(new QueryWrapper<SalesOrder>()
                .select("COALESCE(SUM(total_amount), 0)")
                .in("status", OrderMetricScope.SALES.getStatuses())
                .eq("deleted", 0)
                .between("approved_at", start, end));
        return extractAggregateAmount(result);
    }

    /** 采购单经营金额（按统一状态、approved_at 和时间段在数据库中聚合）。 */
    private BigDecimal sumPurchaseApprovedBetween(LocalDateTime start, LocalDateTime end) {
        List<Object> result = purchaseOrderMapper.selectObjs(new QueryWrapper<PurchaseOrder>()
                .select("COALESCE(SUM(total_amount), 0)")
                .in("status", OrderMetricScope.PURCHASE.getStatuses())
                .eq("deleted", 0)
                .between("approved_at", start, end));
        return extractAggregateAmount(result);
    }

    /** MyBatis 聚合结果会随驱动返回 Long、BigDecimal 或字符串，这里统一为金额原始分值。 */
    private static BigDecimal extractAggregateAmount(List<Object> result) {
        if (result == null || result.isEmpty() || result.getFirst() == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(result.getFirst()));
    }

    /** 按当前账号可见的待办来源组合快照，保证本期与昨日基线使用相同口径。 */
    private static long sumVisiblePendingOrders(PendingOrderSnapshot snapshot,
                                                boolean canPurchase,
                                                boolean canSales,
                                                boolean canWarehouse) {
        long total = 0L;
        if (canPurchase) {
            total += snapshot.pendingPurchaseOrderApproval() + snapshot.pendingPurchaseReturnApproval();
        }
        if (canSales) {
            total += snapshot.pendingSalesOrderApproval() + snapshot.pendingSalesReturnApproval();
        }
        if (canWarehouse) {
            total += snapshot.pendingInboundConfirmation() + snapshot.pendingOutboundConfirmation();
        }
        return total;
    }
    /** 退货单待审批与销售、采购订单待审批使用相同业务权限口径。 */
    private long countPendingReturns(ReturnType returnType) {
        return returnOrderMapper.selectCount(new LambdaQueryWrapper<ReturnOrder>()
                .eq(ReturnOrder::getReturnType, returnType.name())
                .eq(ReturnOrder::getStatus, ReturnStatus.SUBMITTED.name())
                .eq(ReturnOrder::getDeleted, 0));
    }
    /** 构建指标VO */
    private static DashboardMetricVO buildMetric(MetricKey key, BigDecimal value,
                                                 BigDecimal changeRate, MetricStatus status) {
        DashboardMetricVO vo = new DashboardMetricVO();
        vo.setKey(key);
        vo.setLabel(key.getLabel());
        vo.setValue(value);
        vo.setUnit(key.getUnit());
        vo.setChangeRate(changeRate);
        vo.setCompareText(changeRate != null ? key.getCompareText() : null);
        vo.setStatus(status);
        return vo;
    }
}