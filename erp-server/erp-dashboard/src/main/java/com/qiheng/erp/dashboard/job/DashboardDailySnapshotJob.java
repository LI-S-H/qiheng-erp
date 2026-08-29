package com.qiheng.erp.dashboard.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.annotation.DistributedLock;
import com.qiheng.erp.dashboard.cache.DashboardPrevValueCache;
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

/**
 * 工作台运营类指标日快照任务。
 *
 * <p>每日 23:55 拍当前时刻的"待处理订单数"与"库存风险 SKU 数"快照，
 * 写入 Redis 日快照 key（TTL 3 天），供次日工作台"较昨日"变化率对比使用。</p>
 *
 * <p>快照为全公司口径（不区分用户权限），与首屏指标的 prev 对比期一致。
 * 多实例部署时通过 {@code @DistributedLock}（Redisson RLock）保证只有一个实例执行。</p>
 *
 * @author Li
 * @since 2026-08-29
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DashboardDailySnapshotJob {

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
    @DistributedLock(key = LOCK_KEY, leaseTime = 300)
    public void snapshot() {
        LocalDate today = LocalDate.now();
        try {
            long pendingCount = countPendingOrders();
            prevValueCache.putDailySnapshot(today,
                    DashboardPrevValueCache.DailySnapshotType.PENDING_COUNT,
                    BigDecimal.valueOf(pendingCount));

            long stockRisk = stockAlertLoader.countRiskSkus();
            prevValueCache.putDailySnapshot(today,
                    DashboardPrevValueCache.DailySnapshotType.STOCK_RISK_COUNT,
                    BigDecimal.valueOf(stockRisk));

            log.info("工作台日快照完成 date={} pendingCount={} stockRisk={}", today, pendingCount, stockRisk);
        } catch (Exception ex) {
            log.error("工作台日快照执行失败 date={}", today, ex);
        }
    }

    /** 全公司口径待处理订单总数：待审采购 + 待审销售 + 待确认入库 + 待确认出库 */
    private long countPendingOrders() {
        long pendingPurchase = purchaseOrderMapper.selectCount(
                new LambdaQueryWrapper<PurchaseOrder>()
                        .eq(PurchaseOrder::getStatus, PurchaseOrderStatus.SUBMITTED.name())
                        .eq(PurchaseOrder::getDeleted, 0));
        long pendingSales = salesOrderMapper.selectCount(
                new LambdaQueryWrapper<SalesOrder>()
                        .eq(SalesOrder::getStatus, SalesOrderStatus.SUBMITTED.name())
                        .eq(SalesOrder::getDeleted, 0));
        long pendingInbound = inboundBillMapper.selectCount(
                new LambdaQueryWrapper<InboundBill>()
                        .eq(InboundBill::getStatus, StockBillStatus.PENDING_CONFIRM.name()));
        long pendingOutbound = outboundBillMapper.selectCount(
                new LambdaQueryWrapper<OutboundBill>()
                        .eq(OutboundBill::getStatus, StockBillStatus.PENDING_CONFIRM.name()));
        return pendingPurchase + pendingSales + pendingInbound + pendingOutbound;
    }
}
