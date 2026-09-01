package com.qiheng.erp.dashboard.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.dashboard.cache.DashboardPrevValueCache;
import com.qiheng.erp.dashboard.cache.model.PendingOrderSnapshot;
import com.qiheng.erp.dashboard.loader.DashboardStockAlertLoader;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.enums.SalesOrderStatus;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBill;
import com.qiheng.erp.warehouse.domain.stockbill.enums.StockBillStatus;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import com.qiheng.erp.warehouse.mapper.OutboundBillMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.Supplier;

/**
 * 工作台运营类指标日快照任务。
 *
 * <p>每日 23:55 拍当前时刻的"待处理订单分项"与"库存风险 SKU 数"快照，
 * 写入 Redis 日快照 key（TTL 3 天），供次日工作台"较昨日"变化率对比使用。</p>
 *
 * <p>待处理订单快照按来源拆分为采购、销售、入库和出库四项；首屏读取时再按
 * 当前用户权限组合。多实例部署时通过 {@code @DistributedLock}（Redisson RLock）保证只有一个实例执行。</p>
 *
 * @author Li
 * @since 2026-08-29
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DashboardDailySnapshotJob {

    private static final int MAX_RETRY = 3;
    private static final long BASE_DELAY_MS = 1000L;

    /** Redisson 分布式锁 key（SpEL 字符串字面量语法），多实例部署时只允许一个实例执行 */
    private static final String LOCK_KEY = "'dashboard:job:daily-snapshot'";

    private final DashboardPrevValueCache prevValueCache;
    private final DashboardStockAlertLoader stockAlertLoader;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final SalesOrderMapper salesOrderMapper;
    private final InboundBillMapper inboundBillMapper;
    private final OutboundBillMapper outboundBillMapper;

    /**
     * 每日 23:55 执行快照
     *
     * <p>选择 23:55 而非次日 00:30 的原因：让"当天日期"的 key 在当天结束前就写好，
     * 次日 00:00 起用户访问即可命中缓存，避免跨天调度间隙的 30 分钟 miss 窗口。</p>
     */
    @Scheduled(cron = "0 55 23 * * ?")
    @DistributedLock(key = LOCK_KEY, leaseTime = 60)
    public void snapshot() {
        LocalDate today = LocalDate.now();
        // 重试 3 次，每次间隔 1 秒
        boolean pendingOk = retryStep("待处理订单分项", today, () -> {
            PendingOrderSnapshot snapshot = snapshotPendingOrders();
            prevValueCache.putPendingOrderSnapshot(today, snapshot);
            return snapshot;
        });
        // 重试 3 次，每次间隔 1 秒 线式重试
        boolean stockOk = retryStep("库存风险SKU数", today, () -> {
            long stockRisk = stockAlertLoader.countRiskSkus();
            prevValueCache.putDailySnapshot(today,
                    DashboardPrevValueCache.DailySnapshotType.STOCK_RISK_COUNT,
                    BigDecimal.valueOf(stockRisk));
            return stockRisk;
        });

        if (pendingOk && stockOk) {
            log.info("工作台日快照全部完成 date={}", today);
        } else {
            log.warn("工作台日快照部分失败 date={} pendingOk={} stockOk={}", today, pendingOk, stockOk);
        }
    }

    private <T> boolean retryStep(String stepName, LocalDate today, Supplier<T> action) {
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                T result = action.get();
                log.info("快照步骤[{}]成功 date={} result={}", stepName, today, result);
                return true;
            } catch (Exception ex) {
                log.error("快照步骤[{}]失败 date={} 第{}/{}次", stepName, today, attempt, MAX_RETRY, ex);
                if (attempt < MAX_RETRY) {
                    try {
                        Thread.sleep(BASE_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("重试等待被中断，终止重试 date={} step={}", today, stepName);
                        // TODO: 处理中断异常，如记录日志、通知管理员等
                        break;
                    }
                }
            }
        }
        // TODO: 处理最大重试次数超过，如记录日志、通知管理员等
        return false;
    }

    /** 全公司口径待处理订单分项：待审采购、待审销售、待确认入库、待确认出库。 */
    private PendingOrderSnapshot snapshotPendingOrders() {
        // 待审采购订单数
        long pendingPurchase = purchaseOrderMapper.selectCount(
                new LambdaQueryWrapper<PurchaseOrder>()
                        .eq(PurchaseOrder::getStatus, PurchaseOrderStatus.SUBMITTED.name()));
        // 待审销售订单数
        long pendingSales = salesOrderMapper.selectCount(
                new LambdaQueryWrapper<SalesOrder>()
                        .eq(SalesOrder::getStatus, SalesOrderStatus.SUBMITTED.name()));
        // 待确认入库单数
        long pendingInbound = inboundBillMapper.selectCount(
                new LambdaQueryWrapper<InboundBill>()
                        .eq(InboundBill::getStatus, StockBillStatus.PENDING_CONFIRM.name()));
        // 待确认出库单数
        long pendingOutbound = outboundBillMapper.selectCount(
                new LambdaQueryWrapper<OutboundBill>()
                        .eq(OutboundBill::getStatus, StockBillStatus.PENDING_CONFIRM.name()));
        return new PendingOrderSnapshot(pendingPurchase, pendingSales, pendingInbound, pendingOutbound);
    }
}