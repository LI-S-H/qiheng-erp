package com.qiheng.erp.dashboard.job;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.dashboard.cache.DashboardPrevValueCache;
import com.qiheng.erp.dashboard.domain.enums.OrderMetricScope;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.function.Supplier;

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

    private static final int MAX_RETRY = 3;
    private static final long BASE_DELAY_MS = 1000L;

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
        LocalDateTime start = month.atDay(1).atStartOfDay();
        LocalDateTime end = month.atEndOfMonth().atTime(23, 59, 59);

        boolean salesOk = retryStep("销售累计", month, () -> {
            BigDecimal salesTotal = sumSalesApprovedBetween(start, end);
            prevValueCache.putMonthlySnapshot(month,
                    DashboardPrevValueCache.MonthlySnapshotType.SALES_TOTAL, salesTotal);
            return salesTotal;
        });

        boolean purchaseOk = retryStep("采购累计", month, () -> {
            BigDecimal purchaseTotal = sumPurchaseApprovedBetween(start, end);
            prevValueCache.putMonthlySnapshot(month,
                    DashboardPrevValueCache.MonthlySnapshotType.PURCHASE_TOTAL, purchaseTotal);
            return purchaseTotal;
        });

        if (salesOk && purchaseOk) {
            log.info("工作台月快照全部完成 month={}", month);
        } else {
            log.warn("工作台月快照部分失败 month={} salesOk={} purchaseOk={}", month, salesOk, purchaseOk);

        }
    }

    private <T> boolean retryStep(String stepName, YearMonth month, Supplier<T> action) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                T result = action.get();
                log.info("快照步骤[{}]成功 month={} result={}", stepName, month, result);
                return true;
            } catch (Exception ex) {
                log.error("快照步骤[{}]失败 month={} 第{}/{}次", stepName, month, attempt, MAX_RETRY, ex);
                if (attempt < MAX_RETRY) {
                    try {
                        Thread.sleep(BASE_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("重试等待被中断，终止重试 month={} step={}", month, stepName);
                        // TODO: 处理中断异常，如记录日志、通知管理员等
                        break;
                    }
                }
            }
        }
        // TODO: 处理失败异常，如记录日志、通知管理员等
        return false;
    }

    /** 月度销售快照金额（按统一状态、approved_at 和时间段在数据库中聚合）。 */
    private BigDecimal sumSalesApprovedBetween(LocalDateTime start, java.time.LocalDateTime end) {
        List<Object> result = salesOrderMapper.selectObjs(new QueryWrapper<SalesOrder>()
                .select("COALESCE(SUM(total_amount), 0)")
                .in("status", OrderMetricScope.SALES.getStatuses())
                .eq("deleted", 0)
                .between("approved_at", start, end));
        return extractAggregateAmount(result);
    }

    /** 月度采购快照金额（按统一状态、approved_at 和时间段在数据库中聚合）。 */
    private BigDecimal sumPurchaseApprovedBetween(LocalDateTime start, LocalDateTime end) {
        List<Object> result = purchaseOrderMapper.selectObjs(new QueryWrapper<PurchaseOrder>()
                .select("COALESCE(SUM(total_amount), 0)")
                .in("status", OrderMetricScope.PURCHASE.getStatuses())
                .eq("deleted", 0)
                .between("approved_at", start, end));
        return extractAggregateAmount(result);
    }

    /** MyBatis 聚合结果会随驱动返回 Long、BigDecimal 或字符串，这里统一为金额原始分值。 */
    private static BigDecimal extractAggregateAmount(java.util.List<Object> result) {
        if (result == null || result.isEmpty() || result.getFirst() == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(result.getFirst()));
    }
}