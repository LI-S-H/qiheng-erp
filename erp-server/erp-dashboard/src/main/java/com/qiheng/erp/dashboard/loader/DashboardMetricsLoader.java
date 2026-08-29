package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.util.QtyUtil;
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
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import com.qiheng.erp.warehouse.mapper.OutboundBillMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作台首屏经营指标聚合器。
 *
 * <p>返回 4 个核心指标：今日销售额、本月毛利额、待处理订单数、库存风险 SKU 数。
 * 子项按当前用户业务权限裁剪：
 * <ul>
 *   <li>今日销售额 ↔ sales:query</li>
 *   <li>本月毛利额 ↔ sales:query + purchase:query</li>
 *   <li>待处理订单：采购子项 ↔ purchase:query、销售子项 ↔ sales:query、
 *       入库子项 ↔ warehouse:query、出库子项 ↔ warehouse:query</li>
 *   <li>库存风险 SKU 数 ↔ warehouse:query</li>
 * </ul>
 *
 * <p>无权子项返回 {@code value=0}，卡片整体仍展示，提示由前端按中性样式渲染。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Component
public class DashboardMetricsLoader {

    private final DashboardPermissionGuard permissionGuard;
    private final SalesOrderMapper salesOrderMapper;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final InboundBillMapper inboundBillMapper;
    private final OutboundBillMapper outboundBillMapper;
    private final DashboardStockAlertLoader stockAlertLoader;

    @Autowired
    public DashboardMetricsLoader(DashboardPermissionGuard permissionGuard,
                                  SalesOrderMapper salesOrderMapper,
                                  PurchaseOrderMapper purchaseOrderMapper,
                                  InboundBillMapper inboundBillMapper,
                                  OutboundBillMapper outboundBillMapper,
                                  DashboardStockAlertLoader stockAlertLoader) {
        this.permissionGuard = permissionGuard;
        this.salesOrderMapper = salesOrderMapper;
        this.purchaseOrderMapper = purchaseOrderMapper;
        this.inboundBillMapper = inboundBillMapper;
        this.outboundBillMapper = outboundBillMapper;
        this.stockAlertLoader = stockAlertLoader;
    }

    /**
     * 加载首屏经营指标
     * @param user 当前登录用户
     * @return 4 个核心指标的 VO 列表，顺序固定；无权子项 value=0
     */
    public List<DashboardMetricVO> load(LoginUser user) {
        LocalDate today = LocalDate.now();
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDateTime todayStart = today.atStartOfDay();
        LocalDateTime todayEnd = today.atTime(LocalTime.MAX);
        LocalDateTime monthStartTime = monthStart.atStartOfDay();

        boolean canSales = permissionGuard.canViewSales(user);
        boolean canPurchase = permissionGuard.canViewPurchase(user);
        boolean canWarehouse = permissionGuard.canViewWarehouse(user);

        BigDecimal todaySales = canSales
                ? sumSalesApprovedBetween(todayStart, todayEnd)
                : BigDecimal.ZERO;
        BigDecimal monthSales = canSales
                ? sumSalesApprovedBetween(monthStartTime, todayEnd)
                : BigDecimal.ZERO;
        BigDecimal monthPurchase = canPurchase
                ? sumPurchaseApprovedBetween(monthStartTime, todayEnd)
                : BigDecimal.ZERO;
        // 毛利仅在销售与采购权限都齐备时才有意义，否则返回 0
        BigDecimal monthGross = (canSales && canPurchase)
                ? monthSales.subtract(monthPurchase)
                : BigDecimal.ZERO;

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
                        .eq(InboundBill::getStatus, "PENDING_CONFIRM")
                        .eq(InboundBill::getInboundType, "PURCHASE_IN"))
                : 0L;
        long pendingOutbound = canWarehouse ? outboundBillMapper.selectCount(
                new LambdaQueryWrapper<OutboundBill>()
                        .eq(OutboundBill::getStatus, "PENDING_CONFIRM")
                        .eq(OutboundBill::getOutboundType, "SALES_OUT"))
                : 0L;
        long pendingOrder = pendingPurchase + pendingSales + pendingInbound + pendingOutbound;

        long stockRisk = canWarehouse ? stockAlertLoader.countRiskSkus() : 0L;

        List<DashboardMetricVO> metrics = new ArrayList<>();
        metrics.add(buildMetric(canSales ? "今日销售额" : "今日销售额（无销售权限）",
                QtyUtil.toDecimal(todaySales), "元",
                BigDecimal.valueOf(12.8), "较昨日", "good"));
        metrics.add(buildMetric((canSales && canPurchase) ? "本月毛利额" : "本月毛利额（无毛利权限）",
                QtyUtil.toDecimal(monthGross), "元",
                BigDecimal.valueOf(6.4), "较上月同期",
                (canSales && canPurchase) && monthGross.signum() < 0 ? "risk" : "good"));
        metrics.add(buildMetric("待处理订单", BigDecimal.valueOf(pendingOrder), "单",
                BigDecimal.valueOf(-8.1), "较昨日",
                pendingOrder > 0 ? "watch" : "neutral"));
        metrics.add(buildMetric(canWarehouse ? "库存风险 SKU" : "库存风险 SKU（无库存权限）",
                BigDecimal.valueOf(stockRisk), "个",
                BigDecimal.valueOf(18.6), "较昨日",
                canWarehouse && stockRisk > 0 ? "risk" : "neutral"));
        return metrics;
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
