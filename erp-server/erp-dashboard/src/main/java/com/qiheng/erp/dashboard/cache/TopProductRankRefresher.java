package com.qiheng.erp.dashboard.cache;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.dashboard.domain.enums.OrderMetricScope;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrderItem;
import com.qiheng.erp.returnorder.domain.enums.ReturnStatus;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.mapper.ReturnOrderItemMapper;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrderItem;
import com.qiheng.erp.sales.mapper.SalesOrderItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台 TOP 商品排行凌晨全量重建任务。
 *
 * <p>每日 01:30 跑一次：SQL 查近 30 天过审销售订单明细 + 销售退货审批通过的明细，
 * 按商品聚合"销售金额 - 销售退货金额"作为净销售额；写入 Redis 替换旧数据，
 * RENAME 原子切换保证工作台不会读到空窗口。白天业务事件通过 {@link TopProductRankCache}
 * 增量加减分。采购退货不影响"卖了多少"，不参与聚合。</p>
 *
 * @author Li
 * @since 2026-09-02
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TopProductRankRefresher {

    private final SalesOrderItemMapper salesOrderItemMapper;
    private final ReturnOrderItemMapper returnOrderItemMapper;
    private final TopProductRankCache rankCache;

    /** 凌晨 01:30 跑：避开月初月末高频审批时段，30 天滑动窗口首日跨过凌晨即重建 */
    @Scheduled(cron = "0 30 1 * * ?")
    public void refresh() {
        TopProductRankCache.RebuildOutcome outcome = rankCache.rebuild(this::queryRankEntries);
        // 区分三种结果：成功 / 被其他实例抢占 / 失败，便于监控告警
        switch (outcome) {
            case SUCCESS -> log.info("工作台 TOP 商品排行凌晨全量重建成功");
            case SKIPPED_LOCK_BUSY -> log.info("工作台 TOP 商品排行凌晨全量重建跳过：其他实例正在跑");
            case FAILED -> log.warn("工作台 TOP 商品排行凌晨全量重建失败，需要人工排查或等明天重试");
        }
    }

    /** SQL 查近 30 天明细并按 product_id 聚合净销售额；可由凌晨 job、首次冷启动与手动重算共用 */
    public List<TopProductRankCache.RankEntry> queryRankEntries() {
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(TopProductRankCache.WINDOW_DAYS - 1L);
        LocalDateTime endTime = today.atTime(23, 59, 59);

        // inSql 不支持参数绑定；时间来自本地日期，按固定格式写入固定 SQL 片段。
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String startTime = startDate.atStartOfDay().format(dateTimeFormatter);
        String endTimeText = endTime.format(dateTimeFormatter);
        // 1. 查询近 30 天过审销售订单明细（销售金额为正）
        List<SalesOrderItem> saleItems = salesOrderItemMapper.selectList(new QueryWrapper<SalesOrderItem>()
                .inSql("sales_order_id",
                        "SELECT id FROM sales_order WHERE deleted = 0 AND status IN ("
                                + "'" + OrderMetricScope.SALES.getStatuses().get(0) + "','"
                                + OrderMetricScope.SALES.getStatuses().get(1) + "','"
                                + OrderMetricScope.SALES.getStatuses().get(2)
                                + "') AND approved_at BETWEEN '" + startTime + "' AND '" + endTimeText + "'"));
        // 2. 查询近 30 天销售退货（按退货单的审批时间聚合，与销售订单对齐时间口径）
        //    采购退货（PURCHASE_RETURN）不影响"卖了多少"，不参与聚合
        List<ReturnOrderItem> returnItems = returnOrderItemMapper.selectList(new QueryWrapper<ReturnOrderItem>()
                .inSql("return_order_id",
                        "SELECT id FROM return_order WHERE deleted = 0 AND return_type = '"
                                + ReturnType.SALES_RETURN.name()
                                + "' AND status IN ('"
                                + ReturnStatus.APPROVED.name() + "','"
                                + ReturnStatus.PARTIAL_EXECUTED.name() + "','"
                                + ReturnStatus.COMPLETED.name()
                                + "') AND approved_at BETWEEN '" + startTime + "' AND '" + endTimeText + "'"));
        // 3. 聚合：销售为正、退货为负
        Map<Long, long[]> agg = new HashMap<>();
        for (SalesOrderItem item : saleItems) {
            long productId = item.getProductId();
            if (productId == 0L) {
                continue;
            }
            long[] bucket = agg.computeIfAbsent(productId, k -> new long[2]);
            bucket[0] += nullSafe(item.getTotalAmount());
            bucket[1] += nullSafe(item.getQuantity());
        }
        for (ReturnOrderItem item : returnItems) {
            long productId = item.getProductId();
            if (productId == 0L) {
                continue;
            }
            long[] bucket = agg.computeIfAbsent(productId, k -> new long[2]);
            // 退货直接扣减明细总金额与审批数量，与销售口径对齐
            bucket[0] -= toApprovedReturnAmount(item);
            bucket[1] -= nullSafe(item.getApprovedQty());
        }
        // 4. 仅保留净金额为正的商品（完全退货的商品不应再上榜）
        List<TopProductRankCache.RankEntry> entries = new ArrayList<>(agg.size());
        for (Map.Entry<Long, long[]> entry : agg.entrySet()) {
            long[] bucket = entry.getValue();
            if (bucket[0] > 0) {
                entries.add(new TopProductRankCache.RankEntry(
                        entry.getKey(), bucket[0], bucket[1]));
            }
        }
        return entries;
    }

    /** 与实时事件保持一致：金额按审批数量和单价换算，不能使用申请数量对应的 totalAmount。 */
    private static long toApprovedReturnAmount(ReturnOrderItem item) {
        return QtyUtil.toStored(QtyUtil.toDecimal(nullSafe(item.getApprovedQty()))
                .multiply(QtyUtil.toDecimal(nullSafe(item.getUnitPrice()))));
    }

    private static long nullSafe(Long value) {
        return value == null ? 0L : value;
    }
}