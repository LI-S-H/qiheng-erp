package com.qiheng.erp.dashboard.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.dashboard.cache.DashboardPrevValueCache;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.enums.SalesOrderStatus;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 工作台财务类指标月快照任务。
 *
 * <p>在每月最后一天 23:55 拍"本月整月销售累计 / 采购累计"快照，
 * 写入 Redis 月快照 key（TTL 90 天），供次月工作台"较上月"变化率对比使用。</p>
 *
 * <p>Spring cron 不支持月末 L 标记，因此调度窗口设为 28~31 日的 23:55，
 * 任务内部判断今天是否为本月最后一天，非末日直接跳过（幂等）。</p>
 *
 * <p>多实例部署时通过 {@code @DistributedLock}（Redisson RLock）保证只有一个实例执行。</p>
 *
 * @author Li
 * @since 2026-08-29
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DashboardMonthlySnapshotJob {

    /** Redisson 分布式锁 key（SpEL 字符串字面量语法），多实例部署时只允许一个实例执行 */
    private static final String LOCK_KEY = "'dashboard:job:monthly-snapshot'";

    private final DashboardPrevValueCache prevValueCache;
    private final SalesOrderMapper salesOrderMapper;
    private final PurchaseOrderMapper purchaseOrderMapper;

    /**
     * 每月 28~31 日的 23:55 触发；任务内部判断今天是否为本月最后一天，非末日直接跳过
     */
    @Scheduled(cron = "0 55 23 28-31 * ?")
    @DistributedLock(key = LOCK_KEY, leaseTime = 300)
    public void snapshot() {
        LocalDate today = LocalDate.now();
        YearMonth currentMonth = YearMonth.from(today);
        if (!today.equals(currentMonth.atEndOfMonth())) {
            // 非本月最后一天（28/29/30 命中调度窗口但非末日），幂等跳过
            return;
        }
        snapshotOfMonth(currentMonth);
    }

    /**
     * 拍指定月份的整月快照（供调度入口与手动触发共用）
     *
     * <p>手动触发时 month 传当月即可：本月尚未结束，拍的是"截至此刻"的累计值，
     * 月末调度会再次覆盖为完整整月值。</p>
     *
     * @param month 目标月份
     */
    @DistributedLock(key = LOCK_KEY, leaseTime = 300)
    public void snapshotOfMonth(YearMonth month) {
        try {
            BigDecimal salesTotal = sumSalesApprovedBetween(
                    month.atDay(1).atStartOfDay(),
                    month.atEndOfMonth().atTime(java.time.LocalTime.MAX));
            prevValueCache.putMonthlySnapshot(month,
                    DashboardPrevValueCache.MonthlySnapshotType.SALES_TOTAL, salesTotal);

            BigDecimal purchaseTotal = sumPurchaseApprovedBetween(
                    month.atDay(1).atStartOfDay(),
                    month.atEndOfMonth().atTime(java.time.LocalTime.MAX));
            prevValueCache.putMonthlySnapshot(month,
                    DashboardPrevValueCache.MonthlySnapshotType.PURCHASE_TOTAL, purchaseTotal);

            log.info("工作台月快照完成 month={} salesTotal={} purchaseTotal={}",
                    month, QtyUtil.toDecimal(salesTotal), QtyUtil.toDecimal(purchaseTotal));
        } catch (Exception ex) {
            log.error("工作台月快照执行失败 month={}", month, ex);
        }
    }

    /** 本月整月销售累计（已过审且出库完成的订单，按 approved_at 聚合） */
    private BigDecimal sumSalesApprovedBetween(java.time.LocalDateTime start, java.time.LocalDateTime end) {
        java.util.List<SalesOrder> orders = salesOrderMapper.selectList(
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

    /** 本月整月采购累计（已审核且入库完成的订单，按 approved_at 聚合） */
    private BigDecimal sumPurchaseApprovedBetween(java.time.LocalDateTime start, java.time.LocalDateTime end) {
        java.util.List<PurchaseOrder> orders = purchaseOrderMapper.selectList(
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
}
